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

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Runnable that is scheduled in the future to report leaks.  The ScheduledFuture is cancelled if
 * the connection is closed before the leak time expires.
 *
 * @author huyu
 */
class ProxyLeakTask implements Runnable {

  private static final Logger LOGGER = LoggerFactory.getLogger(ProxyLeakTask.class);
  //空实现
  static final ProxyLeakTask NO_LEAK;
  //调度任务
  private ScheduledFuture<?> scheduledFuture;
  //条目名称
  private String entryName;
  //用于打印堆栈
  private Exception exception;
  //线程名称
  private String threadName;
  //是否泄漏
  private boolean isLeaked;

  static {
    //JIT可优化掉
    NO_LEAK = new ProxyLeakTask() {
      @Override
      void schedule(ScheduledExecutorService executorService, long leakDetectionThreshold) {
      }

      @Override
      public void run() {
      }

      @Override
      public void cancel() {
      }
    };
  }

  ProxyLeakTask(final PoolEntryHolder poolEntry) {
    this.exception = new Exception("Apparent entry leak detected");
    this.threadName = Thread.currentThread().getName();
    this.entryName = poolEntry.entry().toString();
  }

  private ProxyLeakTask() {
  }

  void schedule(ScheduledExecutorService executorService, long leakDetectionThreshold) {
    scheduledFuture = executorService.schedule(this, leakDetectionThreshold, TimeUnit.MILLISECONDS);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void run() {
    isLeaked = true;

    final var stackTrace = exception.getStackTrace();
    final var trace = new StackTraceElement[stackTrace.length - 5];

    System.arraycopy(stackTrace, 5, trace, 0, trace.length);

    exception.setStackTrace(trace);

    LOGGER.warn("entry leak detection triggered for {} on thread {}, stack trace follows",
        entryName, threadName, exception);
  }

  void cancel() {
    scheduledFuture.cancel(false);
    if (isLeaked) {
      LOGGER.info(
          "Previously reported leaked entry {} on thread {} was returned to the pool (unleaked)",
          entryName, threadName);
    }
  }
}
