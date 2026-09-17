package com.lian.aicode.common;

import com.lian.aicode.exception.ErrorCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;

/**
 * API 统一响应结构。
 *
 * <p>业务数据始终放在 {@code data}，错误通过 {@code code/message} 表达，便于前端拦截器统一处理。</p>
 *
 * @param <T> 业务数据类型
 */
@Getter
@Setter
public class BaseResponse<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private int code;

    private T data;

    private String message;

    public BaseResponse(int code, T data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    public BaseResponse(ErrorCode errorCode) {
        this(errorCode.getCode(), null, errorCode.getMessage());
    }
}
