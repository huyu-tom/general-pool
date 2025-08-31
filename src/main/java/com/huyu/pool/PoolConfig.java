package com.huyu.pool;

/**
 * 池参数配置
 */
public class PoolConfig {


  /**
   * 池条目空闲时间
   */
  private volatile long IdleTimeout;

  /**
   * 池条目最大生存时间
   */
  private volatile long maxLifetime;

  /**
   * 池最大容量(最大连接数)
   */
  private volatile int maxPoolSize;

  /**
   * 池最小空闲连接数
   */
  private volatile int minIdle;

  /**
   * 获取池条目的超时时间(单位:毫秒)
   */
  private volatile int fetchTimeout;

  /**
   * 池的名称
   */
  private String poolName;


  public long getIdleTimeout() {
    return IdleTimeout;
  }

  public PoolConfig setIdleTimeout(long idleTimeout) {
    IdleTimeout = idleTimeout;
    return this;
  }


  public long getMaxLifetime() {
    return maxLifetime;
  }

  public PoolConfig setMaxLifetime(long maxLifetime) {
    this.maxLifetime = maxLifetime;
    return this;
  }

  public int getMaxPoolSize() {
    return maxPoolSize;
  }

  public PoolConfig setMaxPoolSize(int maxPoolSize) {
    this.maxPoolSize = maxPoolSize;
    return this;
  }

  public int getMinIdle() {
    return minIdle;
  }

  public PoolConfig setMinIdle(int minIdle) {
    this.minIdle = minIdle;
    return this;
  }

  public String getPoolName() {
    return poolName;
  }

  public PoolConfig setPoolName(String poolName) {
    this.poolName = poolName;
    return this;
  }

  public int getFetchTimeout() {
    return fetchTimeout;
  }

  public PoolConfig setFetchTimeout(int fetchTimeout) {
    this.fetchTimeout = fetchTimeout;
    return this;
  }
}
