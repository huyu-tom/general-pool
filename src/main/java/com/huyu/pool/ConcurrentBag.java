/*
 * Copyright (C) 2013, 2014 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.huyu.pool;

import static com.huyu.pool.ClockSource.currentTime;
import static com.huyu.pool.ClockSource.elapsedNanos;
import static com.huyu.pool.IConcurrentBagEntryHolder.STATE_IN_USE;
import static com.huyu.pool.IConcurrentBagEntryHolder.STATE_NOT_IN_USE;
import static com.huyu.pool.IConcurrentBagEntryHolder.STATE_REMOVED;
import static com.huyu.pool.IConcurrentBagEntryHolder.STATE_RESERVED;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.locks.LockSupport.parkNanos;

import com.huyu.pool.utils.FastList;
import com.huyu.pool.utils.ThreadUtils;
import jakarta.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @param <T>
 */
public class ConcurrentBag<T extends IConcurrentBagEntryHolder> implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrentBag.class);

  /**
   * 为了做到快速借出的话,我们添加的顺序是尾部添加,然后
   */
  private final Collection<T> sharedList;

  /**
   * 弱引用的目的是:
   */
  private final ThreadLocal<List<WeakReference<T>>> threadList;

  private final IBagStateListener listener;

  private final AtomicInteger waiters;

  private volatile boolean closed;

  private final SynchronousQueue<T> handoffQueue;


  /**
   * Construct a ConcurrentBag with the specified listener.
   *
   * @param listener the IBagStateListener to attach to this bag
   */
  public ConcurrentBag(final IBagStateListener listener) {
    this.listener = listener;
    this.handoffQueue = new SynchronousQueue<>(false);
    this.waiters = new AtomicInteger();
    //也是为了快速借出,在归还的时候添加到头部(采用LRU的机制)
    this.sharedList = new ConcurrentLinkedHashSet<>();
    //为了达到快速借出的话,归还添加的时候是尾部添加,然后我们借出的时候是尾部遍历
    this.threadList = ThreadUtils.createThreadLocal(() -> new FastList<>(WeakReference.class, 16));
  }

  private boolean isSupportGetFromThreadLocal() {
    return ThreadUtils.isCarrierThreadLocalAvailable() || !Thread.currentThread().isVirtual();
  }


  /**
   * 借出
   *
   * @param timeout  how long to wait before giving up, in units of unit
   * @param timeUnit a <code>TimeUnit</code> determining how to interpret the timeout parameter
   * @return a borrowed instance from the bag or null if a timeout occurs
   * @throws InterruptedException if interrupted while waiting
   */
  public T borrow(long timeout, final TimeUnit timeUnit, @Nullable Predicate<T> predicate)
      throws InterruptedException {

    final boolean emptyPredicate = Objects.isNull(predicate);

    predicate = predicate == null ? (bag) -> true : predicate;

    // Try the thread-local list first
    // 不是虚拟线程或者说是支持CarrierThreadLocal我们才从线程本地获取
    if (isSupportGetFromThreadLocal()) {
      final var list = threadList.get();
      for (var i = list.size() - 1; i >= 0; i--) {
        final WeakReference<T> reference = list.remove(i);
        final var bagEntry = reference.get();
        if (bagEntry != null && predicate.test(bagEntry) && bagEntry.compareAndSet(STATE_NOT_IN_USE,
            STATE_IN_USE)) {
          list.remove(i);
          return bagEntry;
        }
      }
    }

    // Otherwise, scan the shared list ... then poll the handoff queue
    final int waiting = waiters.incrementAndGet();

    try {
      for (T bagEntry : sharedList) {
        if (predicate.test(bagEntry) && bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
          if (waiting > 1) {
            addBagItem(emptyPredicate, waiting - 1);
          }
          return bagEntry;
        }
      }

      addBagItem(emptyPredicate, waiting);

      timeout = timeUnit.toNanos(timeout);
      do {
        final var start = currentTime();
        final T bagEntry = handoffQueue.poll(timeout, NANOSECONDS);
        if (bagEntry == null || emptyPredicate
            || predicate.test(bagEntry) && bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
          return bagEntry;
        }
        timeout -= elapsedNanos(start);
      } while (timeout > 10_000);
      return null;
    } finally {
      waiters.decrementAndGet();
    }
  }


  private void addBagItem(final boolean emptyPredicate, final int waiting) {
    if (emptyPredicate) {
      listener.addBagItem(waiting);
    } else {
      listener.addBagItemWithPredicate(waiting);
    }
  }

  /**
   * 归还 This method will return a borrowed object to the bag.  Objects that are borrowed from the
   * bag but never "requited" will result in a memory leak.
   *
   * @param bagEntry the value to return to the bag
   * @throws NullPointerException  if value is null
   * @throws IllegalStateException if the bagEntry was not borrowed from the bag
   */
  public void requite(final T bagEntry) {
    //设置为未使用
    bagEntry.setState(STATE_NOT_IN_USE);

    //目的: ConcurrentLinkedHashSet底层进行add的时候,会将这个bagEntry放在第一个遍历
    //将最近归还的就可以快速借出,提高借出的效率(LRU,最近使用的归还到前面)
    sharedList.add(bagEntry);

    for (var i = 0; waiters.get() > 0; i++) {
      if (bagEntry.getState() != STATE_NOT_IN_USE || handoffQueue.offer(bagEntry)) {
        //必须是未使用并且加入offer成功
        return;
      } else if ((i & 0xff) == 0xff) {
        //让该线程进行短暂的休眠,不让他使用CPU
        parkNanos(MICROSECONDS.toNanos(10));
      } else {
        //让出CPU的使用权,但是还是会争夺
        Thread.yield();
      }
    }

    //由于采用固定线程池异步请求,所以尽量将大量的数据放入到threadLocal当中提高速度
    //不进行限制大小,尽量将所有的数据都能平摊到对应的threadLocal当中
    if (isSupportGetFromThreadLocal()) {
      final var threadLocalList = threadList.get();
      threadLocalList.add(new WeakReference<>(bagEntry));
    }
  }

  /**
   * 创建连接 Add a new object to the bag for others to borrow.
   *
   * @param bagEntry an object to add to the bag
   */
  public void add(final T bagEntry) {
    if (closed) {
      LOGGER.info("ConcurrentBag has been closed, ignoring add()");
      throw new IllegalStateException("ConcurrentBag has been closed, ignoring add()");
    }

    sharedList.add(bagEntry);

    // spin until a thread takes it or none are waiting
    while (waiters.get() > 0 && bagEntry.getState() == STATE_NOT_IN_USE && !handoffQueue.offer(
        bagEntry)) {
      Thread.yield();
    }
  }

  /**
   * Remove a value from the bag.  This method should only be called with objects obtained by
   * <code>borrow(long, TimeUnit)</code> or <code>reserve(T)</code>
   *
   * @param bagEntry the value to remove
   * @return true if the entry was removed, false otherwise
   * @throws IllegalStateException if an attempt is made to remove an object from the bag that was
   *                               not borrowed or reserved first
   */
  public boolean remove(final T bagEntry) {
    if (!bagEntry.compareAndSet(STATE_IN_USE, STATE_REMOVED) && !bagEntry.compareAndSet(
        STATE_RESERVED, STATE_REMOVED) && !closed) {
      //直接从使用状态变成移除状态
      //从保留状态变成移除状态
      //并且池没有关闭
      LOGGER.warn("Attempt to remove an object from the bag that was not borrowed or reserved: {}",
          bagEntry);
      return false;
    }

    final boolean removed = sharedList.remove(bagEntry);
    if (!removed && !closed) {
      LOGGER.warn("Attempt to remove an object from the bag that does not exist: {}", bagEntry);
    }

    //不一定remove的线程是归还的线程,所以为什么需要弱引用包裹
    if (isSupportGetFromThreadLocal()) {
      threadList.get().remove(bagEntry);
    }
    return removed;
  }

  /**
   * Close the bag to further adds.
   */
  @Override
  public void close() {
    closed = true;
  }

  /**
   * This method provides a "snapshot" in time of the BagEntry items in the bag in the specified
   * state.  It does not "lock" or reserve items in any way.  Call <code>reserve(T)</code> on items
   * in list before performing any action on them.
   *
   * @param state one of the {@link IConcurrentBagEntryHolder} states
   * @return a possibly empty list of objects having the state specified
   */
  public List<T> values(final int state) {
    final var list = sharedList.stream().filter(e -> e.getState() == state)
        .collect(Collectors.toList());
    Collections.reverse(list);
    return list;
  }

  /**
   * This method provides a "snapshot" in time of the bag items.  It does not "lock" or reserve
   * items in any way.  Call <code>reserve(T)</code> on items in the list, or understand the
   * concurrency implications of modifying items, before performing any action on them.
   *
   * @return a possibly empty list of (all) bag items
   */
  @SuppressWarnings("unchecked")
  public Collection<T> values() {
    return sharedList;
  }

  /**
   * The method is used to make an item in the bag "unavailable" for borrowing.  It is primarily
   * used when wanting to operate on items returned by the <code>values(int)</code> method.  Items
   * that are reserved can be removed from the bag via <code>remove(T)</code> without the need to
   * unreserve them.  Items that are not removed from the bag can be make available for borrowing
   * again by calling the <code>unreserve(T)</code> method.
   *
   * @param bagEntry the item to reserve
   * @return true if the item was able to be reserved, false otherwise
   */
  public boolean reserve(final T bagEntry) {
    return bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_RESERVED);
  }

  /**
   * This method is used to make an item reserved via <code>reserve(T)</code> available again for
   * borrowing.
   *
   * @param bagEntry the item to unreserve
   */
  @SuppressWarnings("SpellCheckingInspection")
  public void unreserve(final T bagEntry) {
    if (bagEntry.compareAndSet(STATE_RESERVED, STATE_NOT_IN_USE)) {
      // spin until a thread takes it or none are waiting
      while (waiters.get() > 0 && !handoffQueue.offer(bagEntry)) {
        Thread.yield();
      }
    } else {
      LOGGER.warn("Attempt to relinquish an object to the bag that was not reserved: {}", bagEntry);
    }
  }

  /**
   * Get the number of threads pending (waiting) for an item from the bag to become available.
   *
   * @return the number of threads waiting for items from the bag
   */
  public int getWaitingThreadCount() {
    return waiters.get();
  }

  /**
   * Get a count of the number of items in the specified state at the time of this call.
   *
   * @param state the state of the items to count
   * @return a count of how many items in the bag are in the specified state
   */
  public int getCount(final int state) {
    var count = 0;
    for (var e : sharedList) {
      if (e.getState() == state) {
        count++;
      }
    }
    return count;
  }

  public int[] getStateCounts() {
    final var states = new int[6];
    for (var e : sharedList) {
      ++states[e.getState()];
    }
    states[4] = sharedList.size();
    states[5] = waiters.get();
    return states;
  }

  /**
   * Get the total number of items in the bag.
   *
   * @return the number of items in the bag
   */
  public int size() {
    return sharedList.size();
  }

  public void dumpState() {
    sharedList.forEach(entry -> LOGGER.info(entry.toString()));
  }
}
