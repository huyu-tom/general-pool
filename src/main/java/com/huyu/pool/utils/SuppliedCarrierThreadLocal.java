package com.huyu.pool.utils;

import java.util.Objects;
import java.util.function.Supplier;
import jdk.internal.misc.CarrierThreadLocal;

public class SuppliedCarrierThreadLocal<T> extends CarrierThreadLocal<T> {

  private final Supplier<? extends T> supplier;

  public SuppliedCarrierThreadLocal(Supplier<? extends T> supplier) {
    this.supplier = Objects.requireNonNull(supplier);
  }

  @Override
  protected T initialValue() {
    return supplier.get();
  }

  public static <T> ThreadLocal<T> createCarrierThreadLocal(Supplier<? extends T> supplier) {
    return new CarrierThreadLocal<>() {
      @Override
      protected T initialValue() {
        return supplier.get();
      }
    };
  }
}
