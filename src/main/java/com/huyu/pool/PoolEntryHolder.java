/*
 * Copyright (C) 2014 Brett Wooldridge
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

import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 条目的持有者(池中的存储元素)
 *
 * @author huyu
 */
final class PoolEntryHolder<T> implements IConcurrentBagEntryHolder {

  private static final Logger LOGGER = LoggerFactory.getLogger(PoolEntryHolder.class);
  private static final AtomicIntegerFieldUpdater<PoolEntryHolder> stateUpdater;
  //在池中,大概率创建频率不高,就不采用LongAddr
  private static final AtomicLong ID = new AtomicLong();

  long lastAccessed;
  long lastBorrowed;
  final long id;

  @SuppressWarnings("FieldCanBeLocal")
  private volatile int state = 0;
  private volatile boolean evict;

  private T entry;

  private volatile ScheduledFuture<?> endOfLife;

  private final EntryPool entryPool;


  static {
    stateUpdater = AtomicIntegerFieldUpdater.newUpdater(PoolEntryHolder.class, "state");
  }

  PoolEntryHolder(final T entry, final EntryPool entryPool) {
    this.entry = entry;
    this.lastAccessed = currentTime();
    this.entryPool = entryPool;
    this.id = ID.incrementAndGet();
  }

  /**
   * Release this entry back to the pool.
   */
  void recycle() {
    if (entry != null) {
      this.lastAccessed = currentTime();
      entryPool.recycle(this);
    }
  }

  T entry() {
    return entry;
  }

  /**
   * Set the end of life {@link ScheduledFuture}.
   *
   * @param endOfLife this PoolEntry/Connection's end of life {@link ScheduledFuture}
   */
  void setFutureEol(final ScheduledFuture<?> endOfLife) {
    this.endOfLife = endOfLife;
  }


  String getPoolName() {
    return entryPool.toString();
  }

  boolean isMarkedEvicted() {
    return evict;
  }

  void markEvicted() {
    this.evict = true;
  }


  /**
   * 驱逐
   *
   * @param closureReason
   */
  void evict(final String closureReason) {
    entryPool.closeEntry(this, closureReason);
  }

  /**
   * Returns millis since lastBorrowed
   */
  long getMillisSinceBorrowed() {
    return elapsedMillis(lastBorrowed);
  }

  EntryPool getPoolBase() {
    return entryPool;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String toString() {
    final var now = currentTime();
    return entry + ", accessed " + elapsedDisplayString(lastAccessed, now) + " ago, "
        + stateToString();
  }

// ***********************************************************************
//                      IConcurrentBagEntry methods
// ***********************************************************************

  /**
   * {@inheritDoc}
   */
  @Override
  public int getState() {
    return stateUpdater.get(this);
  }


  /**
   * {@inheritDoc}
   */
  @Override
  public boolean compareAndSet(int expect, int update) {
    return stateUpdater.compareAndSet(this, expect, update);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setState(int update) {
    stateUpdater.set(this, update);
  }

  T close() {
    var eol = endOfLife;
    if (eol != null && !eol.isDone() && !eol.cancel(false)) {
      LOGGER.warn(
          "{} - maxLifeTime expiration task cancellation unexpectedly returned false for connection {}",
          getPoolName(), entry);
    }
    var tmp = entry;
    entry = null;
    endOfLife = null;
    return tmp;
  }

  private String stateToString() {
    switch (state) {
      case STATE_IN_USE:
        return "IN_USE";
      case STATE_NOT_IN_USE:
        return "NOT_IN_USE";
      case STATE_REMOVED:
        return "REMOVED";
      case STATE_RESERVED:
        return "RESERVED";
      default:
        return "Invalid";
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    PoolEntryHolder<?> that = (PoolEntryHolder<?>) o;
    return id == that.id;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
