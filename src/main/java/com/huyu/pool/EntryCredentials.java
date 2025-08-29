package com.huyu.pool;

/**
 * 条目凭证
 *
 * @param <T>
 */
public class EntryCredentials<T extends Entry> {

  final PoolEntryHolder<T> holder;
  final T entry;

  public EntryCredentials(PoolEntryHolder<T> holder) {
    this.holder = holder;
    this.entry = holder.entry();
  }

  /**
   * 获取条目
   *
   * @return
   */
  public T getEntry() {
    return entry;
  }

  /**
   * 归还
   */
  public void recycle() {
    //归还清除本身的数据
    entry.clear();
    this.holder.recycle();
  }
}
