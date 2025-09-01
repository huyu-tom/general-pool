/*
 * Copyright (C) 2013,2014 Brett Wooldridge
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
import static com.huyu.pool.ClockSource.elapsedDisplayString;
import static com.huyu.pool.ClockSource.elapsedMillis;
import static com.huyu.pool.ClockSource.plusMillis;
import static com.huyu.pool.IConcurrentBagEntryHolder.STATE_IN_USE;
import static com.huyu.pool.IConcurrentBagEntryHolder.STATE_NOT_IN_USE;
import static com.huyu.pool.utils.ThreadUtils.createThreadPoolExecutor;
import static com.huyu.pool.utils.ThreadUtils.quietlySleep;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.huyu.pool.utils.ThreadUtils.DefaultThreadFactory;
import jakarta.annotation.Nullable;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对象池
 *
 * @author huyu
 */
public final class EntryPool<T extends Entry> implements IBagStateListener {

  private final Logger LOGGER = LoggerFactory.getLogger(EntryPool.class);

  public static final int POOL_NORMAL = 0;
  public static final int POOL_SHUTDOWN = 2;

  public volatile int poolState;
  private String poolName;


  // 500ms
  private final long aliveBypassWindowMs = Long.getLong("com.huyu.pool.aliveBypassWindowMs",
      MILLISECONDS.toMillis(500));

  //30s
  private final long housekeepingPeriodMs = Long.getLong("com.huyu.pool.housekeeping.periodMs",
      SECONDS.toMillis(30));

  private static final String EVICTED_CONNECTION_MESSAGE = "(entry was evicted)";

  private static final String DEAD_CONNECTION_MESSAGE = "(entry is dead)";


  //无条件的条目创建者
  private final PoolEntryCreator poolEntryCreator = new PoolEntryCreator();

  //带有条件的条目创建者
  private final PoolEntryCreator poolEntryCreatorWithPredicate = new PoolEntryCreator(true);

  private final PoolEntryCreator postFillPoolEntryCreator = new PoolEntryCreator("After adding ");

  private final ThreadPoolExecutor addEntryExecutor;

  private final ThreadPoolExecutor clearEntryExecutor;

  //核心包
  private final IConcurrentBag<PoolEntryHolder<T>> entryBag;

  //保持最小条目池(当池空闲的时候,会将扩充的池条目减少到最小条目) 和 设置每个条目的最长生存时间 任务的执行器
  private final ScheduledExecutorService houseKeepingExecutorService;

  //保持最小条目池的任务
  private ScheduledFuture<?> houseKeeperTask;

  //条目工厂
  private final EntryFactory entryFactory;

  //池配置
  private final PoolConfig poolConfig;

  //借出的时候的超时时间
  private long fetchTimeout;

  //泄漏工厂
  private ProxyLeakTaskFactory proxyLeakTaskFactory;

  /**
   * Construct a HikariPool with the specified configuration.
   *
   * @param poolConfig   池配置
   * @param entryFactory 条目工厂
   */
  public EntryPool(final PoolConfig poolConfig, final EntryFactory entryFactory) {

    this.entryFactory = entryFactory;
    this.poolConfig = poolConfig;

    fetchTimeout = poolConfig.getFetchTimeout();

    this.entryBag = new ConcurrentBag<>(this);

    this.houseKeepingExecutorService = initializeHouseKeepingExecutorService();

    ThreadFactory threadFactory = null;

    final int maxPoolSize = poolConfig.getMaxPoolSize();
    this.poolName = poolConfig.getPoolName();

    LinkedBlockingQueue<Runnable> addConnectionQueue = new LinkedBlockingQueue<>(maxPoolSize);

    //添加条目执行器
    this.addEntryExecutor = createThreadPoolExecutor(addConnectionQueue, poolName + " entry adder",
        threadFactory, new CustomDiscardPolicy());
    addEntryExecutor.setMaximumPoolSize(Math.min(16, Runtime.getRuntime().availableProcessors()));
    addEntryExecutor.setCorePoolSize(Math.min(16, Runtime.getRuntime().availableProcessors()));
    addEntryExecutor.allowCoreThreadTimeOut(false);

    //清除条目执行器 1个核心,阻塞队列个数(设置最大池个数)
    this.clearEntryExecutor = createThreadPoolExecutor(maxPoolSize, poolName + " entry closer",
        threadFactory, new ThreadPoolExecutor.CallerRunsPolicy());

    //保持最小(驱逐) => 30s执行一次
    this.houseKeeperTask = houseKeepingExecutorService.scheduleWithFixedDelay(new HouseKeeper(),
        100L, housekeepingPeriodMs, MILLISECONDS);

    //泄露工厂
    proxyLeakTaskFactory = new ProxyLeakTaskFactory(poolConfig.getLeakDetectionThreshold(),
        houseKeepingExecutorService);
  }


  /**
   * @return
   * @throws SQLException
   */
  public EntryCredentials<T> getEntry() throws TimeoutException {
    return getEntry(fetchTimeout <= 0 ? Long.MAX_VALUE : fetchTimeout);
  }


  /**
   * @return
   * @throws SQLException
   */
  public EntryCredentials<T> getEntry(Predicate<T> predicate) throws TimeoutException {
    return getEntry(fetchTimeout <= 0 ? Long.MAX_VALUE : fetchTimeout, predicate);
  }


  /**
   * 在指定的时间内获取一个条目
   *
   * @param hardTimeout
   * @return
   * @throws TimeoutException
   */
  public EntryCredentials<T> getEntry(final long hardTimeout) throws TimeoutException {
    return getEntry(hardTimeout, null);
  }


  public EntryCredentials<T> getEntry(final long hardTimeout, @Nullable Predicate<T> predicate)
      throws TimeoutException {
    final var startTime = currentTime();
    try {
      var timeout = hardTimeout;
      do {
        var poolEntryHolder = entryBag.borrow(timeout, MILLISECONDS,
            Objects.nonNull(predicate) ? (holder) -> predicate.test(holder.entry()) : null);

        if (poolEntryHolder == null) {
          break; // We timed out... break and throw exception
        }

        final var now = currentTime();
        if (poolEntryHolder.isMarkedEvicted() || (
            elapsedMillis(poolEntryHolder.lastAccessed, now) > aliveBypassWindowMs && isEntryDead(
                poolEntryHolder.entry()))) {
          closeEntry(poolEntryHolder, poolEntryHolder.isMarkedEvicted() ? EVICTED_CONNECTION_MESSAGE
              : DEAD_CONNECTION_MESSAGE);
          timeout = hardTimeout - elapsedMillis(startTime);
        } else {
          //增加泄漏检测
          ProxyLeakTask proxyLeakTask = proxyLeakTaskFactory.schedule(poolEntryHolder);
          poolEntryHolder.setProxyLeakTask(proxyLeakTask);
          return new EntryCredentials<>(poolEntryHolder);
        }
      } while (timeout > 0L);
      throw new TimeoutException("get entry timeout");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(poolName + " - Interrupted during connection acquisition", e);
    } catch (TimeoutException timeoutException) {
      throw timeoutException;
    } catch (Throwable e) {
      LOGGER.error("get entry Unexpected exception ", e);
      throw new RuntimeException(poolName + " - get entry Unexpected exception ", e);
    }
  }

  /**
   * 条目是否死亡
   *
   * @param entry
   * @return
   */
  private boolean isEntryDead(T entry) {
    return entry.dead();
  }

  /**
   * Shutdown the pool, closing all idle connections and aborting or closing active connections.
   *
   * @throws InterruptedException thrown if the thread is interrupted during shutdown
   */
  public synchronized void shutdown() throws Exception {
    try {
      poolState = POOL_SHUTDOWN;

      if (addEntryExecutor == null) { // pool never started
        return;
      }

      logPoolState("Before shutdown ");

      if (houseKeeperTask != null) {
        houseKeeperTask.cancel(false);
        houseKeeperTask = null;
      }

//      驱逐条目
      softEvictEntry();

      addEntryExecutor.shutdown();
//      if (!addEntryExecutor.awaitTermination(getLoginTimeout(), SECONDS)) {
//        LOGGER.warn("Timed-out waiting for add connection executor to shutdown");
//      }

      destroyHouseKeepingExecutorService();

      entryBag.close();

      final var start = currentTime();
      do {
        softEvictEntry();
      } while (getTotalEntry() > 0 && elapsedMillis(start) < SECONDS.toMillis(10));

      clearEntryExecutor.shutdown();
      if (!clearEntryExecutor.awaitTermination(10L, SECONDS)) {
        LOGGER.warn("Timed-out waiting for clear entry executor to shutdown");
      }
    } finally {
      logPoolState("After shutdown ");
    }
  }


  /**
   * 驱逐条目(用户自己来关闭条目)
   *
   * @param entry
   */
  public void evictEntry(EntryCredentials entry) {
    softEvictEntry(entry.holder, "(entry evicted by user)", true /* owner */);
  }

  // ***********************************************************************
  //                        IBagStateListener callback
  // ***********************************************************************

  /**
   * {@inheritDoc}
   */
  @Override
  public void addBagItem(final int waiting) {
    if (waiting > addEntryExecutor.getQueue().size()) {
      addEntryExecutor.submit(poolEntryCreator);
    }
  }

  @Override
  public void addBagItemWithPredicate(int waiting) {
    if (waiting > addEntryExecutor.getQueue().size()) {
      //等待数 > 积压的个数 (说明还要继续添加)
      addEntryExecutor.submit(poolEntryCreatorWithPredicate);
    }
  }


  /**
   * 获取正在使用的条目(借出去的条目)
   *
   * @return
   */
  public int getActiveEntry() {
    return entryBag.getCount(STATE_IN_USE);
  }


  /**
   * 获取空闲的条目
   *
   * @return
   */
  public int getIdleEntry() {
    return entryBag.getCount(STATE_NOT_IN_USE);
  }


  /**
   * 获取总条目
   *
   * @return
   */
  public int getTotalEntry() {
    return entryBag.size();
  }


  /**
   * 获取条目时候阻塞等待的线程数(该值过大,影响性能(说明数据创建不过来))
   *
   * @return
   */
  public int getThreadsAwaitingEntry() {
    return entryBag.getWaitingThreadCount();
  }


  /**
   * 驱除池中所有的条目,如果被借出的话,就无法驱除
   */
  public void softEvictEntry() {
    entryBag.values().forEach(poolEntryHolder -> softEvictEntry(poolEntryHolder, "(entry evicted)",
        false /* not owner */));
  }

  // ***********************************************************************
  //                           Package methods
  // ***********************************************************************

  /**
   * Log the current pool state at debug level.
   *
   * @param prefix an optional prefix to prepend the log message
   */
  void logPoolState(String... prefix) {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("{} - {}stats (total={}, active={}, idle={}, waiting={})", poolName,
          (prefix.length > 0 ? prefix[0] : ""), getTotalEntry(), getActiveEntry(), getIdleEntry(),
          getThreadsAwaitingEntry());
    }
  }

  /**
   * Recycle PoolEntry (add back to the pool)
   *
   * @param poolEntryHolder the PoolEntry to recycle
   */
  void recycle(final PoolEntryHolder poolEntryHolder) {
    entryBag.requite(poolEntryHolder);
  }

  /**
   * Permanently close the real (underlying) connection (eat any exception).
   *
   * @param poolEntryHolder poolEntry having the connection to close
   * @param closureReason   reason to close
   */
  void closeEntry(final PoolEntryHolder poolEntryHolder, final String closureReason) {
    if (entryBag.remove(poolEntryHolder)) {
      clearEntryExecutor.execute(() -> {
        poolEntryHolder.close();
        if (poolState == POOL_NORMAL) {
          fillPool(false);
        }
      });
    }
  }

  @SuppressWarnings("unused")
  int[] getPoolStateCounts() {
    return entryBag.getStateCounts();
  }

  // ***********************************************************************
  //                           Private methods
  // ***********************************************************************

  /**
   * 创建新的 poolEntry。  如果配置了 maxLifetime，则使用以下命令创建未来的寿命终止任务 与 maxLifetime 时间有 2.5% 的差异，以确保连接不会出现大规模死亡
   * 在泳池里。
   */
  private PoolEntryHolder createPoolEntry() {
    try {
      final var poolEntry = newPoolEntry();
      final var maxLifetime = poolConfig.getMaxLifetime();
      if (maxLifetime > 0) {
        // variance up to 2.5% of the maxlifetime
        final var variance =
            maxLifetime > 10_000 ? ThreadLocalRandom.current().nextLong(maxLifetime / 40) : 0;
        final var lifetime = maxLifetime - variance;
        poolEntry.setFutureEol(
            houseKeepingExecutorService.schedule(new MaxLifetimeTask(poolEntry), lifetime,
                MILLISECONDS));
      }
      return poolEntry;
    } catch (Exception e) {
      if (poolState
          == POOL_NORMAL) { // we check POOL_NORMAL to avoid a flood of messages if shutdown() is running concurrently
        LOGGER.debug("{} - Cannot acquire entry from factory", poolName, e);
      }
    }

    return null;
  }


  private PoolEntryHolder newPoolEntry() {
    return new PoolEntryHolder(entryFactory.createInstance(), this);
  }

  /**
   * Fill pool up from current idle connections (as they are perceived at the point of execution) to
   * minimumIdle connections.
   */
  private synchronized void fillPool(final boolean isAfterAdd) {
    final var idle = getIdleEntry();
    final var shouldAdd =
        getTotalEntry() < poolConfig.getMaxPoolSize() && idle < poolConfig.getMinIdle();

    if (shouldAdd) {
      final var countToAdd = poolConfig.getMinIdle() - idle;
      for (int i = 0; i < countToAdd; i++) {
        addEntryExecutor.submit(isAfterAdd ? postFillPoolEntryCreator : poolEntryCreator);
      }
    } else if (isAfterAdd) {
      LOGGER.debug("{} - Fill pool skipped, pool has sufficient level or currently being filled.",
          poolName);
    }
  }


  /**
   * Log the Throwable that caused pool initialization to fail, and then throw a
   * PoolInitializationException with that cause attached.
   *
   * @param t the Throwable that caused the pool to fail to initialize (possibly null)
   */
  private void throwPoolInitializationException(Throwable t) {
    destroyHouseKeepingExecutorService();
    throw new PoolInitializationException(t);
  }


  private boolean softEvictEntry(final PoolEntryHolder poolEntryHolder, final String reason,
      final boolean owner) {
    //标记驱逐
    poolEntryHolder.markEvicted();

    //保留状态(必须是未使用)
    if (owner || entryBag.reserve(poolEntryHolder)) {
      closeEntry(poolEntryHolder, reason);
      return true;
    }

    return false;
  }


  /**
   * 保持存活性的执行器
   *
   * @return
   */
  private ScheduledExecutorService initializeHouseKeepingExecutorService() {
    final var executor = new ScheduledThreadPoolExecutor(1,
        new DefaultThreadFactory(poolName + " housekeeper"), new CustomDiscardPolicy());
    executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }

  /**
   * Destroy (/shutdown) the Housekeeping service Executor, if it was the one that we created.
   */
  private void destroyHouseKeepingExecutorService() {
    houseKeepingExecutorService.shutdownNow();
  }

// ***********************************************************************
//                      Non-anonymous Inner-classes
// ***********************************************************************

  /**
   * Creating and adding poolEntries (connections) to the pool. 创建连接
   */
  private final class PoolEntryCreator implements Callable<Boolean> {

    private final String loggingPrefix;
    private final boolean hasPredicate;

    PoolEntryCreator(boolean hasPredicate) {
      this(null, hasPredicate);
    }

    PoolEntryCreator(String loggingPrefix) {
      this(loggingPrefix, false);
    }

    PoolEntryCreator() {
      this(null, false);
    }

    PoolEntryCreator(String loggingPrefix, boolean hasPredicate) {
      this.loggingPrefix = loggingPrefix;
      this.hasPredicate = hasPredicate;
    }

    @Override
    public Boolean call() {

      var backoffMs = 10L;
      var added = false;

      try {
        while (shouldContinueCreating()) {
          final var poolEntry = createPoolEntry();
          if (poolEntry != null) {
            added = true;
            entryBag.add(poolEntry);
            LOGGER.debug("{} - Added entry {}", poolName, poolEntry.entry());
            break;
          } else {  // failed to get connection from db, sleep and retry
            if (loggingPrefix != null && backoffMs % 50 == 0) {
              LOGGER.debug("{} - entry add failed, sleeping with backoff: {}ms", poolName,
                  backoffMs);
            }
            quietlySleep(backoffMs);
            backoffMs = Math.min(SECONDS.toMillis(5), backoffMs * 2);
          }
        }
      } finally {
        if (added && loggingPrefix != null) {
          logPoolState(loggingPrefix);
        } else if (!added) {
          logPoolState("entry not added, ");
        }
      }

      // Pool is suspended, shutdown, or at max size
      return Boolean.FALSE;
    }

    /**
     * We only create connections if we need another idle connection or have threads still waiting
     * for a new connection.  Otherwise we bail out of the request to create.
     *
     * @return true if we should create a connection, false if the need has disappeared
     */
    private boolean shouldContinueCreating() {
      synchronized (PoolEntryCreator.class) {
        //在池中有空闲的条目个数
        final int idleEntry = getIdleEntry();
        //等待的线程数
        final int waitingThreadCount = entryBag.getWaitingThreadCount();
        //池的状态是正常的 并且  池是否满了 并且
        return poolState == POOL_NORMAL && getTotalEntry() < poolConfig.getMaxPoolSize() && (
            //说明池中的空闲个数还不足,没有达到设定的值
            idleEntry < poolConfig.getMinIdle() ||
                //还有人在等待者 (有条件的话,有可能空闲的不符合条件,所以这里加上一个有条件判断，有条件就不进行判断空闲和等待线程个数)
                (hasPredicate && waitingThreadCount > 0)
                //还有人在等待着
                || waitingThreadCount > idleEntry);
      }
    }
  }


  /**
   * 退出并维持最少空闲连接的内务管理任务。
   */
  private final class HouseKeeper implements Runnable {

    //上一次执行的时间
    private volatile long previous = plusMillis(currentTime(), -housekeepingPeriodMs);

    @Override
    public void run() {
      try {
        // refresh values in case they changed via MBean
        final var idleTimeout = poolConfig.getIdleTimeout();
        final var now = currentTime();

        // Detect retrograde time, allowing +128ms as per NTP spec.
        if (plusMillis(now, 128) < plusMillis(previous, housekeepingPeriodMs)) {
          LOGGER.warn(
              "{} - Retrograde clock change detected (housekeeper delta={}), soft-evicting connections from pool.",
              poolName, elapsedDisplayString(previous, now));
          previous = now;
          // soft-evict from the pool
          softEvictEntry();
          LOGGER.debug("soft-evict from the pool");
          return;
        } else if (now > plusMillis(previous, (3 * housekeepingPeriodMs) / 2)) {
          // No point evicting for forward clock motion, this merely accelerates connection retirement anyway
          LOGGER.warn("{} - Thread starvation or clock leap detected (housekeeper delta={}).",
              poolName, elapsedDisplayString(previous, now));
        }

        previous = now;

        if (idleTimeout > 0L && poolConfig.getMinIdle() < poolConfig.getMaxPoolSize()) {
          logPoolState("Before cleanup ");
          final var notInUse = entryBag.values(STATE_NOT_IN_USE);
          var maxToRemove = notInUse.size() - poolConfig.getMinIdle();
          for (PoolEntryHolder entry : notInUse) {
            if (maxToRemove > 0 && elapsedMillis(entry.lastAccessed, now) > idleTimeout
                && entryBag.reserve(entry)) {
              closeEntry(entry, "(entry has passed idleTimeout)");
              maxToRemove--;
            }
          }
          logPoolState("After cleanup  ");
        } else {
          logPoolState("Pool ");
        }

        fillPool(true); // Try to maintain minimum connections
      } catch (Exception e) {
        LOGGER.error("Unexpected exception in housekeeping task", e);
      }
    }
  }


  /**
   * 保持最长生活时间
   */
  private final class MaxLifetimeTask implements Runnable {

    private final PoolEntryHolder poolEntryHolder;

    MaxLifetimeTask(PoolEntryHolder poolEntryHolder) {
      this.poolEntryHolder = poolEntryHolder;
    }

    public void run() {
      if (softEvictEntry(poolEntryHolder, "(entry has passed maxLifetime)",
          false /* not owner */)) {
//        addBagItem(entryBag.getWaitingThreadCount());
        addBagItemWithPredicate(entryBag.getWaitingThreadCount());
      }
    }
  }


  /**
   * Pool初始化异常
   */
  public static class PoolInitializationException extends RuntimeException {

    private static final long serialVersionUID = 929872118275916520L;

    /**
     * Construct an exception, possibly wrapping the provided Throwable as the cause.
     *
     * @param t the Throwable to wrap
     */
    public PoolInitializationException(Throwable t) {
      super("Failed to initialize pool: " + t.getMessage(), t);
    }
  }


  /**
   * 自定义拒绝策略
   */
  public static class CustomDiscardPolicy implements RejectedExecutionHandler {


    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
    }
  }
}
