package com.huyu.pool;

public abstract class EmptyEntry implements Entry {

  @Override
  public void clear() {

  }

  @Override
  public boolean dead() {
    return false;
  }

  @Override
  public void abort() {

  }
}
