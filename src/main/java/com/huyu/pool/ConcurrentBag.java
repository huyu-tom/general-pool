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
import com.huyu.pool.utils.IWeakReference;
import com.huyu.pool.utils.ThreadUtils;
import jakarta.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
 *
 * Copied from HikariCP, undergo secondary modification
 *
 * @param <T>
 * @author huyu
 */
public class ConcurrentBag<T extends IConcurrentBagEntryHolder> implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrentBag.class);

  /**
   * 采用了LRU的缓存,不采用原先(HikariCP)的CopyOnWriteArrayList
   * <p>
   * 原因:
   * <ul>
   * <li>1. 因为要做到通用池,对于数据库连接池的话,池中的对象一般相对较少,采用CopyOnWriteArrayList性能较高,但是如果是对象池的话,可能是上万甚至更高级别,不是很适合CopyOnWriteArrayList机制</li>
   * <li>2. 由于高版本支持虚拟线程,虽然我采用了jdk.internal.misc.CarrierThreadLocal来解决虚拟线程threadlocal的问题(但是这个是非公开API,需要add-open功能),但是虚拟线程哲学无池化,导致从threadlocal快速借出就无意义,大部分都是
   * 从sharedList借出,会导致后面借出的情况需要遍历之前已被解除的元素,相当于无效遍历,所以采用LRU机制来解决这个问题</li>
   * </ul>
   */
  private final Collection<T> sharedList;

  /**
   * 弱引用的目的是: 防止sharedList的对象已被移除,但是可能threadlocal还存在着
   * 因为借出的时候,如果不在threadlocal借出,但是归还的话,会加入到threadlocal当中
   * 如果后续这个对象被移除(超时,或者达到了最长的生存时间),那么threadlocal中的对象不会被移除(因为调用remove()操作不是之前归还的线程)
   * 导致threadlocal可能存在着垃圾对象(小小的泄露),所以采用弱引用包裹(如果没有被sharedList强引用,就算threadlocal还存在,也是会被gc的)
   */
  private final ThreadLocal<List<IWeakReference<T>>> threadList;

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
    this.threadList = ThreadUtils.createThreadLocal(() -> new FastList<>(IWeakReference.class, 16));
  }


  /**
   * 是否支持从threadlocal获取
   *
   * @return
   */
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
      while (list.size() > 0) {
        WeakReference<T> reference = list.removeLast();
        /**
         * 按理来说我add进去的内容一定不是空的,本能不用判断的,但是采用了jdk.internal.misc.CarrierThreadLocal 可能removeLast()元素为空
         * 可能原因
         * <ul>
         *   <li>虚拟线程来回切换在同一个载体线程(因为载体线程可能自己上下文切换?),里面的size在不同虚拟线程的栈帧上是不同的 </li>
         *   <li>假设虚拟线程1拿到size为10,(切换),虚拟线程2拿到size也是为10,(切换)</li>
         *   <li>虚拟县线程1执行removeLast(),那么size为10的值为null了，(切换)，虚拟线程size为10的元素,removeLast()为null</li>
         * </ul>
         * 注意: 切换逻辑代表切换到同一个载体线程
         */
        if (reference != null) {
          final var bagEntry = reference.get();
          if (bagEntry != null && predicate.test(bagEntry) && bagEntry.compareAndSet(
              STATE_NOT_IN_USE, STATE_IN_USE)) {
            return bagEntry;
          }
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
        //如果指定的时间过了之后,有可能返回null
        final var bagEntry = handoffQueue.poll(timeout, NANOSECONDS);
        if (bagEntry == null || (predicate.test(bagEntry) && bagEntry.compareAndSet(
            STATE_NOT_IN_USE, STATE_IN_USE))) {
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

    if (isSupportGetFromThreadLocal()) {
      final var threadLocalList = threadList.get();
      if (Thread.currentThread().isVirtual()) {
        //因为是虚拟线程的话,采用的是CarrierThreadLocal,他底层是存储到虚拟线程的载体线程当中
        //如果采用虚拟线程的哲学的话,载体线程一般是操作系统的核心数,一般较少,所以虚拟线程的CarrierThreadLocal上需要
        //放置更多的数据,用于快速借出,后期可进行配置
        if (threadLocalList.size() < 1024) {
          threadLocalList.add(new IWeakReference<>(bagEntry));
        }
      } else {
        //如果不是虚拟线程的话,那么采用16个数据
        if (threadLocalList.size() < 16) {
          threadLocalList.add(new IWeakReference<>(bagEntry));
        }
      }
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
      threadList.get().remove(new IWeakReference<>(bagEntry));
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
    // 0 索引: 未使用
    // 1 索引: 已使用
    // 2,3 => 代表此槽未使用
    //  -1 的状态是:  已被移除（不在sharedList列表当中）
    //  -2 的状态是:  再被移除的前夕(即将被移除,但是还在sharedList列表当中),一种中间状态(不可被借出)
    for (var e : sharedList) {
      final int state = e.getState();
      if (state >= 0) {
        ++states[state];
      }
    }
    // 4索引: 总共个数
    states[4] = sharedList.size();
    // 5索引: 等待个数(这个过高,说明数量不足,需要加大最大个数)
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
