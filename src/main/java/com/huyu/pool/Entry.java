package com.huyu.pool;

/**
 * 条目
 */
public interface Entry {

  /**
   * 在归还的时候清理数据
   */
  void clear();

  /**
   * 判断该条目是否能使用,true表示不能使用 false表示能使用
   *
   * @return
   */
  boolean dead();


  /**
   * 终止(在进行对池进行shutdown的时候,可能有一些条目正在使用,如果该方法被调用说明 池需要终止条目)
   */
  void abort();
}
