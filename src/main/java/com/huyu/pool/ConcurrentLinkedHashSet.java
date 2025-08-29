package com.huyu.pool;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import com.googlecode.concurrentlinkedhashmap.Weighers;
import java.util.AbstractSet;
import java.util.Iterator;

public class ConcurrentLinkedHashSet<E> extends AbstractSet<E> implements java.io.Serializable {

  final ConcurrentLinkedHashMap<E, Boolean> map;

  public ConcurrentLinkedHashSet(ConcurrentLinkedHashMap<E, Boolean> map) {
    this.map = map;
  }

  public ConcurrentLinkedHashSet(int initCapacity, int maxCapacity) {
    this.map = new ConcurrentLinkedHashMap.Builder<E, Boolean>().initialCapacity(initCapacity)
        .maximumWeightedCapacity(maxCapacity).weigher(Weighers.entrySingleton()).build();
  }

  public ConcurrentLinkedHashSet(int maxCapacity) {
    this(16, maxCapacity);
  }

  public ConcurrentLinkedHashSet() {
    this(16, Integer.MAX_VALUE);
  }


  /**
   * 从最新add处遍历
   *
   * @return
   */
  @Override
  public Iterator<E> iterator() {
    return map.keySet().iterator();
  }

  @Override
  public int size() {
    return map.size();
  }

  @Override
  public boolean isEmpty() {
    return map.isEmpty();
  }

  @Override
  public boolean contains(Object o) {
    //noinspection SuspiciousMethodCalls
    return map.containsKey(o);
  }

  @Override
  public boolean add(E e) {
    return map.put(e, Boolean.TRUE) == null;
  }

  @Override
  public boolean remove(Object o) {
    return Boolean.TRUE.equals(map.remove(o));
  }

  @Override
  public void clear() {
    map.clear();
  }
}
