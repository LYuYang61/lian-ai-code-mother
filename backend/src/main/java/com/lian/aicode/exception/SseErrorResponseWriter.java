package com.lian.aicode.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 把进入流式响应之前的业务异常以 SSE 事件写回客户端。
 *
 * <p>限流等异常发生在 Controller 方法返回 Flux 之前，此时响应尚未提交、
 * 全局异常处理器仍可控制响应体：直接手写 {@code business-error} + {@code done}
 * 两个标准 SSE 事件，前端 EventSource 就能拿到精确文案，而不是把 JSON 错误体
 * 误判成连接错误。使用自定义事件名是为了不与浏览器对 {@code error} 事件的
 * 内置重连/错误语义冲突。</p>
 *
 * <p>判定条件与教程一致：Accept 头包含 text/event-stream，或请求路径命中
 * SSE 生成接口；普通 JSON 请求不受影响，仍走统一 {@code BaseResponse} 返回。</p>
 */
@Slf4j
public final class SseErrorResponseWriter {

    /** SSE 生成接口的路径特征：该路径上的异常即使 Accept 头缺失也按 SSE 透出。 */
    private static final String SSE_PATH_MARKER = "/chat/gen/code";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SseErrorResponseWriter() {
    }

    /**
     * 判定是否为 SSE 请求并写出错误事件。
     *
     * @return true 表示已按 SSE 处理（响应已提交，调用方应返回 null 放弃 JSON 响应体）
     */
    public static boolean tryWrite(HttpServletRequest request, HttpServletResponse response,
                                   int errorCode, String errorMessage) {
        if (request == null || response == null || !isSseRequest(request)) {
            return false;
        }
        String payload;
        try {
            payload = OBJECT_MAPPER.writeValueAsString(Map.of(
                    "error", true,
                    "code", errorCode,
                    "message", errorMessage == null || errorMessage.isBlank()
                            ? ErrorCode.SYSTEM_ERROR.getMessage() : errorMessage));
        } catch (IOException exception) {
            payload = "{\"error\":true,\"message\":\"生成失败\"}";
        }
        try {
            // 字符集必须在 getWriter 之前设置，否则按 ISO-8859-1 编码中文会乱码。
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/event-stream");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Cache-Control", "no-cache");
            var writer = response.getWriter();
            // 事件行不带空格，与项目中 ServerSentEvent 输出的 "event:done" 格式保持一致。
            writer.write("event:business-error\ndata: " + payload + "\n\n");
            writer.flush();
            // 发送标准结束事件，前端按 done 收尾，避免 EventSource 停留在等待状态或自动重连。
            writer.write("event:done\ndata: {}\n\n");
            writer.flush();
            log.warn("SSE 请求业务异常已以事件透出：uri={}, code={}", request.getRequestURI(), errorCode);
            return true;
        } catch (IOException ioException) {
            // 写入失败说明客户端连接已断开；仍视为已处理，避免再走 JSON 分支产生二次异常。
            log.warn("SSE 错误事件写出失败（客户端可能已断开）：uri={}", request.getRequestURI(), ioException);
            return true;
        }
    }

    private static boolean isSseRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        String uri = request.getRequestURI();
        return (accept != null && accept.contains("text/event-stream"))
                || (uri != null && uri.contains(SSE_PATH_MARKER));
    }
}
