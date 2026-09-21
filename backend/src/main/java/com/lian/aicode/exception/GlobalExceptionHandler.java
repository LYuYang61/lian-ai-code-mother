package com.lian.aicode.exception;

import com.lian.aicode.common.BaseResponse;
import com.lian.aicode.common.ResultUtils;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * 统一转换 REST 接口异常。
 *
 * <p>Knife4j 的 OpenAPI 3 扫描不应把异常处理器当作业务接口；{@link Hidden} 同时规避
 * Spring Boot 3.5 与旧版文档扫描逻辑之间的 ControllerAdvice 兼容问题。</p>
 *
 * <p>这里只处理普通 JSON 请求。未来增加 SSE/流式接口时，应在流处理器中定义专用错误事件，
 * 不要在响应已经提交后再次写普通 JSON。</p>
 */
@Hidden
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> handleBusinessException(BusinessException exception, HttpServletRequest request) {
        log.warn("业务请求失败：method={}, uri={}, code={}",
                request.getMethod(), request.getRequestURI(), exception.getCode());
        return ResultUtils.error(exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public BaseResponse<?> handleUnexpectedException(Exception exception, HttpServletRequest request) {
        // 详细堆栈只写服务端日志，响应不暴露数据库、文件路径或第三方服务信息。
        log.error("未处理的请求异常：method={}, uri={}",
                request.getMethod(), request.getRequestURI(), exception);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public BaseResponse<?> handleValidationException(MethodArgumentNotValidException exception,
                                                     HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse(ErrorCode.PARAMS_ERROR.getMessage());
        log.warn("请求参数校验失败：method={}, uri={}, message={}",
                request.getMethod(), request.getRequestURI(), message);
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public BaseResponse<?> handleUnreadableMessage(HttpMessageNotReadableException exception,
                                                   HttpServletRequest request) {
        log.warn("请求 JSON 格式错误：method={}, uri={}", request.getMethod(), request.getRequestURI());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "请求 JSON 格式错误");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public BaseResponse<?> handleTypeMismatch(MethodArgumentTypeMismatchException exception,
                                              HttpServletRequest request) {
        log.warn("请求参数类型不匹配：method={}, uri={}, parameter={}",
                request.getMethod(), request.getRequestURI(), exception.getName());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "请求参数类型错误");
    }
}
