package com.huyu.pool.utils;

import jakarta.annotation.Nullable;
import java.lang.reflect.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UnsafeUtils {

  private static final Logger LOGGER = LoggerFactory.getLogger(UnsafeUtils.class);

  private static final boolean UNSAFE_AVAILABLE;
  private static final jdk.internal.misc.Unsafe UNSAFE_INSTANCE;

  static {
    boolean isAvailable = false;
    jdk.internal.misc.Unsafe unsafe = null;

    try {
      // 尝试通过反射获取 jdk.internal.misc.Unsafe 实例
      unsafe = getUnsafeInstance();
      isAvailable = unsafe != null;
      if (isAvailable) {
        LOGGER.debug("jdk.internal.misc.Unsafe is available");
      } else {
        LOGGER.debug("jdk.internal.misc.Unsafe is not available");
      }
    } catch (Throwable e) {
      LOGGER.debug("jdk.internal.misc.Unsafe is not available", e);
      isAvailable = false;
      unsafe = null;
    }
    UNSAFE_AVAILABLE = isAvailable;
    UNSAFE_INSTANCE = unsafe;
  }

  /**
   * 判断 jdk.internal.misc.Unsafe 是否可用
   *
   * @return 如果可用返回 true，否则返回 false
   */
  public static boolean isUnsafeAvailable() {
    return UNSAFE_AVAILABLE;
  }

  /**
   * 获取 jdk.internal.misc.Unsafe 实例
   *
   * @return jdk.internal.misc.Unsafe 实例，如果不可用则返回 null
   */
  @Nullable
  public static jdk.internal.misc.Unsafe getUnsafe() {
    return UNSAFE_INSTANCE;
  }

  /**
   * 通过反射获取 jdk.internal.misc.Unsafe 实例
   *
   * @return jdk.internal.misc.Unsafe 实例
   * @throws Exception 获取过程中可能抛出的异常
   */
  @Nullable
  private static jdk.internal.misc.Unsafe getUnsafeInstance() throws Exception {
    try {
      // 尝试通过反射获取 theUnsafe 字段
      Class<jdk.internal.misc.Unsafe> unsafeClass = jdk.internal.misc.Unsafe.class;
      Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
      theUnsafeField.setAccessible(true);
      return (jdk.internal.misc.Unsafe) theUnsafeField.get(null);
    } catch (Exception ex) {
      LOGGER.debug("Failed to get jdk.internal.misc.Unsafe instance via reflection", ex);
      return null;
    }
  }

  /**
   * 私有构造函数，防止实例化
   */
  private UnsafeUtils() {
    // 禁止实例化
  }
}