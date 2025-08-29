package com.huyu.pool.utils;

import static java.lang.Thread.currentThread;
import static java.util.concurrent.TimeUnit.SECONDS;

import jakarta.annotation.Nonnull;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;
import jdk.internal.misc.CarrierThreadLocal;

public class ThreadUtils {

  public static ThreadPoolExecutor createThreadPoolExecutor(final int queueSize,
      final String threadName, ThreadFactory threadFactory, final RejectedExecutionHandler policy) {
    return createThreadPoolExecutor(new LinkedBlockingQueue<>(queueSize), threadName, threadFactory,
        policy);
  }

  public static ThreadPoolExecutor createThreadPoolExecutor(final BlockingQueue<Runnable> queue,
      final String threadName, ThreadFactory threadFactory, final RejectedExecutionHandler policy) {
    if (threadFactory == null) {
      threadFactory = new DefaultThreadFactory(threadName);
    }

    var executor = new ThreadPoolExecutor(1 /*core*/, 1 /*max*/, 5 /*keepalive*/, SECONDS, queue,
        threadFactory, policy);
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }

  public static final class DefaultThreadFactory implements ThreadFactory {

    private final String threadName;
    private final boolean daemon;

    public DefaultThreadFactory(String threadName) {
      this.threadName = threadName;
      this.daemon = true;
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public Thread newThread(Runnable r) {
      var thread = new Thread(r, threadName);
      thread.setDaemon(daemon);
      return thread;
    }
  }


  public static void quietlySleep(final long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      // I said be quiet!
      currentThread().interrupt();
    }
  }

  private static final boolean CARRIER_THREAD_LOCAL_AVAILABLE;

  static {
    boolean isCarrierThreadAvailable = true;
    try {
      Class.forName("jdk.internal.misc.CarrierThreadLocal").newInstance();
    } catch (Throwable e) {
      isCarrierThreadAvailable = false;
    }
    CARRIER_THREAD_LOCAL_AVAILABLE = isCarrierThreadAvailable;
  }


  /**
   * 判断jdk是否支持CarrierThreadLocal
   *
   * @return
   */
  public static boolean isCarrierThreadLocalAvailable() {
    return CARRIER_THREAD_LOCAL_AVAILABLE;
  }


  @Nonnull
  public static <T> ThreadLocal<T> createThreadLocal(Supplier<? extends T> supplier) {
    if (isCarrierThreadLocalAvailable()) {
      return new CarrierThreadLocal<T>() {
        @Override
        protected T initialValue() {
          return supplier.get();
        }
      };
    } else {
      return ThreadLocal.withInitial(supplier);
    }
  }
}
