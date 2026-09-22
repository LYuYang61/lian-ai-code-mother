package com.lian.aicode.ai.model.message;

import lombok.Getter;

/**
 * AI 生成过程对前端公开的事件类型。
 *
 * <p>协议只描述可展示的摘要，不把完整工具参数、模型原始响应或服务器路径直接暴露给
 * 浏览器。新增类型时需要同时更新前端事件渲染和历史消息格式化逻辑。</p>
 */
@Getter
public enum StreamMessageTypeEnum {

    AI_RESPONSE("ai_response", "AI 响应"),
    THINKING("thinking", "思考摘要"),
    TOOL_REQUEST("tool_request", "工具请求"),
    TOOL_EXECUTED("tool_executed", "工具执行结果");

    private final String value;
    private final String text;

    StreamMessageTypeEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }

    public static StreamMessageTypeEnum fromValue(String value) {
        for (StreamMessageTypeEnum type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return null;
    }
}
