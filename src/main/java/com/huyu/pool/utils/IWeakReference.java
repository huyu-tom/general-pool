package com.huyu.pool.utils;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

public class IWeakReference<T> extends WeakReference<T> {

  public IWeakReference(T referent) {
    super(referent);
  }

  public IWeakReference(T referent, ReferenceQueue<? super T> q) {
    super(referent, q);
  }

  @Override
  public boolean equals(Object obj) {
    return get().equals(obj);
  }
}
