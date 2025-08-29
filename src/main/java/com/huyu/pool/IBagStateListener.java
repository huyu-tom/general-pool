package com.huyu.pool;

public interface IBagStateListener {

  /**
   *
   * @param waiting
   */
  void addBagItem(int waiting);

  /**
   * 增加一个判断条件,如果具有判断条件的话,就不能采用 等待数如果小于等于空闲数 ,
   * 会导致借出方法一直等待阻塞(核心原因handoffQueue.poll()方法阻塞等待,如果未设置超时时间的话)
   * 在驱逐之后再次进行添加新的时候,也得调用该方法,要不然会导致有附加判断条件的借出的时候由于(addBagItem的后调是判断当前等待数如果小于等于还可借出的个数的话(休闲个数))
   * 就不会创建,就会导致长时间阻塞
   *
   * @param waiting
   */
  void addBagItemWithPredicate(int waiting);
}
