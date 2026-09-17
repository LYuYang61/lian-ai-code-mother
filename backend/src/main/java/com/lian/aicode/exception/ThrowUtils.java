package com.lian.aicode.exception;

/** 统一封装条件断言，减少业务代码中的重复异常构造。 */
public final class ThrowUtils {

    private ThrowUtils() {
        // 工具类不允许实例化。
    }

    public static void throwIf(boolean condition, RuntimeException exception) {
        if (condition) {
            throw exception;
        }
    }

    public static void throwIf(boolean condition, ErrorCode errorCode) {
        throwIf(condition, new BusinessException(errorCode));
    }

    public static void throwIf(boolean condition, ErrorCode errorCode, String message) {
        throwIf(condition, new BusinessException(errorCode, message));
    }
}
