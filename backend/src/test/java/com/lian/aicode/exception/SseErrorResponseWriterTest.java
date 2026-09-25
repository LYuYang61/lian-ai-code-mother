package com.lian.aicode.exception;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SSE 错误事件写出的分支测试：Accept 判定、事件格式和 JSON 请求不受影响。 */
class SseErrorResponseWriterTest {

    @Test
    void writesBusinessErrorAndDoneEventsForSseAccept() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/app/chat/gen/code");
        request.addHeader("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean handled = SseErrorResponseWriter.tryWrite(request, response,
                ErrorCode.TOO_MANY_REQUEST.getCode(), "AI 对话请求过于频繁，请稍后再试");

        assertTrue(handled, "SSE 请求应被识别并处理");
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertEquals("text/event-stream;charset=UTF-8", response.getContentType());
        String body = response.getContentAsString();
        assertTrue(body.contains("event:business-error"), "应包含 business-error 事件行");
        assertTrue(body.contains("\"code\":42900"), "事件体应包含业务错误码");
        assertTrue(body.contains("AI 对话请求过于频繁"), "事件体应包含用户可见文案");
        assertTrue(body.contains("event:done"), "应以 done 事件收尾");
    }

    @Test
    void ssePathMarkerMatchesEvenWithoutAcceptHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/app/chat/gen/code");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean handled = SseErrorResponseWriter.tryWrite(request, response,
                ErrorCode.NO_AUTH_ERROR.getCode(), "无权限");

        assertTrue(handled, "SSE 生成接口路径应被识别");
        assertTrue(response.getContentAsString().contains("event:business-error"));
    }

    @Test
    void plainJsonRequestIsNotHandled() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/app/add");
        request.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean handled = SseErrorResponseWriter.tryWrite(request, response,
                ErrorCode.PARAMS_ERROR.getCode(), "参数错误");

        assertFalse(handled, "普通 JSON 请求不应按 SSE 处理");
        assertEquals(200, response.getStatus());
    }

    @Test
    void uriWithoutSseMarkerAndAcceptIsIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/app/get/vo");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(SseErrorResponseWriter.tryWrite(request, response,
                ErrorCode.NOT_FOUND_ERROR.getCode(), "不存在"));
    }
}
