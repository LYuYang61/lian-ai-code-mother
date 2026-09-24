package com.lian.aicode.workflow.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 工作流向现有 SSE 通道发送的轻量事件。
 *
 * <p>不包含完整提示词、完整模型输出、服务器绝对路径或第三方密钥；代码片段仍由现有
 * {@code ai_response/tool_executed} 协议负责传输。</p>
 */
@Data
@Builder
public class WorkflowStreamMessage {

    private String type;
    private String step;
    private String message;
    private Integer attempt;
    private Integer imageCount;
    private Integer promptLength;
    private Map<String, Object> data;
}
