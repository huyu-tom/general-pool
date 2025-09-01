package com.huyu.pool;

import jakarta.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;


/**
 * 并发包接口，定义了对象池的核心操作
 *
 * @param <T> 包中元素的类型
 */
public interface IConcurrentBag<T extends IConcurrentBagEntryHolder> extends AutoCloseable {

  /**
   * 借出一个对象
   *
   * @param timeout   等待超时时间
   * @param timeUnit  时间单位
   * @param predicate 对象过滤条件
   * @return 借出的对象，如果超时则返回null
   * @throws InterruptedException 如果等待过程中被中断
   */
  T borrow(long timeout, TimeUnit timeUnit, @Nullable Predicate<T> predicate)
      throws InterruptedException;

  /**
   * 归还一个对象
   *
   * @param bagEntry 要归还的对象
   */
  void requite(T bagEntry);

  /**
   * 添加一个新对象到包中
   *
   * @param bagEntry 要添加的对象
   */
  void add(T bagEntry);

  /**
   * 从包中移除一个对象
   *
   * @param bagEntry 要移除的对象
   * @return 如果成功移除返回true，否则返回false
   */
  boolean remove(T bagEntry);


  /**
   * 获取指定状态的所有对象
   *
   * @param state 对象状态
   * @return 指定状态的对象列表
   */
  List<T> values(int state);

  /**
   * 获取包中所有对象
   *
   * @return 包中所有对象的集合
   */
  Collection<T> values();

  /**
   * 预留一个对象，使其不可被借出
   *
   * @param bagEntry 要预留的对象
   * @return 如果成功预留返回true，否则返回false
   */
  boolean reserve(T bagEntry);

  /**
   * 解除对象的预留状态，使其可以被借出
   *
   * @param bagEntry 要解除预留的对象
   */
  void unreserve(T bagEntry);

  /**
   * 获取正在等待对象的线程数
   *
   * @return 等待线程数
   */
  int getWaitingThreadCount();

  /**
   * 获取指定状态的对象数量
   *
   * @param state 对象状态
   * @return 指定状态的对象数量
   */
  int getCount(int state);

  /**
   * 获取各种状态的对象数量统计
   *
   * @return 包含各种状态计数的数组
   */
  int[] getStateCounts();

  /**
   * 获取包中对象的总数量
   *
   * @return 对象总数量
   */
  int size();

  /**
   * 打印包中所有对象的状态
   */
  void dumpState();
}

