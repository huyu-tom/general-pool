package com.huyu.pool.benchmark;

import com.huyu.pool.Entry;
import com.huyu.pool.EntryCredentials;
import com.huyu.pool.EntryFactory;
import com.huyu.pool.EntryPool;
import com.huyu.pool.PoolConfig;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = {"-Xms4g", "-Xmx4g"})
@Warmup(iterations = 1, time = 5)
@Measurement(iterations = 2, time = 5)
public class PoolJMH {

  private static final Logger LOGGER = LoggerFactory.getLogger(PoolJMH.class);

  /**
   * 池大小
   */
  @Param({"50000"})
  private int poolSize;


  /**
   * 空闲大小
   */
  @Param({"1000"})
  private int mindle;


  private EntryPool<CommonsPoolEntry> pool;
  private GenericObjectPool<CommonsPoolEntry> commonsPool;


  static class CommonsPoolEntry implements Entry {

    @Override
    public void clear() {
      // 模拟清理操作
    }

    @Override
    public boolean dead() {
      return false;
    }

    @Override
    public void abort() {
      // 模拟终止操作
    }

    public void use() {
      // 模拟使用
      Math.sqrt(Math.random() * 1000);
    }
  }

  @Setup(Level.Trial)
  public void setup() {

    pool = new EntryPool<>(
        new PoolConfig().setMinIdle(mindle).setMaxPoolSize(poolSize).setPoolName("general-pool")
            .setFetchTimeout(1000), new EntryFactory() {
      @Override
      public Entry createInstance() {
        return new CommonsPoolEntry();
      }
    });

    // 设置Apache Commons Pool
    GenericObjectPoolConfig<CommonsPoolEntry> config = new GenericObjectPoolConfig<>();
    config.setMaxTotal(poolSize);
    config.setMinIdle(mindle);
    config.setMaxIdle(mindle);
    config.setTestOnBorrow(false);
    config.setTestOnReturn(false);

    commonsPool = new GenericObjectPool<>(new BasePooledObjectFactory<CommonsPoolEntry>() {
      @Override
      public CommonsPoolEntry create() throws Exception {
        return new CommonsPoolEntry();
      }

      @Override
      public PooledObject<CommonsPoolEntry> wrap(CommonsPoolEntry obj) {
        return new DefaultPooledObject<>(obj);
      }
    }, config);
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    try {
      pool.shutdown();
    } catch (Exception e) {
      LOGGER.warn("Error shutting down general pool", e);
    }

    try {
      commonsPool.close();
    } catch (Exception e) {
      LOGGER.warn("Error closing commons pool", e);
    }
  }

  @Benchmark
  @Threads(36)
  public void generalPool(Blackhole blackhole) throws InterruptedException, TimeoutException {
    EntryCredentials<CommonsPoolEntry> entry = null;
    //模拟逻辑
    try {
      entry = pool.getEntry(Long.MAX_VALUE);
      entry.getEntry().use();
    } finally {
      entry.recycle();
      blackhole.consume(entry);
    }
  }

  @Benchmark
  @Threads(36)
  public void commonsPool(Blackhole blackhole) throws Exception {
    CommonsPoolEntry entry = null;
    try {
      entry = commonsPool.borrowObject();
      // 模拟使用
      entry.use();
    } finally {
      commonsPool.returnObject(entry);
      blackhole.consume(entry);
    }
  }


  public static void main(String[] args) throws RunnerException {
    Options options = new OptionsBuilder().include(PoolJMH.class.getSimpleName()).build();
    new Runner(options).run();
  }
}