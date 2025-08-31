package com.huyu.pool;

/**
 * 条目凭证
 *
 * @param <T>
 */
public final class EntryCredentials<T extends Entry> {

  //条目的持有者
  final PoolEntryHolder<T> holder;

  //条目
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
    this.entry.clear();
    this.holder.recycle();
  }
}
