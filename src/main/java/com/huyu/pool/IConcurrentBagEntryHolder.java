package com.huyu.pool;

public interface IConcurrentBagEntryHolder {

  //未在使用(在池中)
  int STATE_NOT_IN_USE = 0;
  //已在使用(借出去了)
  int STATE_IN_USE = 1;
  //已被移除(不在池中)
  int STATE_REMOVED = -1;
  //中间状态(如果在池中无法借出(无法变成状态1),之后可进行删除(可变成状态-1),被撤回的时候可以变成0(表示没有在使用))
  int STATE_RESERVED = -2;

  boolean compareAndSet(int expectState, int newState);

  void setState(int newState);

  int getState();
}
