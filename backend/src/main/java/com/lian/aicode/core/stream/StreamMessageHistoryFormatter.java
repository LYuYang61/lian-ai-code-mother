package com.lian.aicode.core.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lian.aicode.ai.model.message.StreamMessageTypeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 将内部 JSON 事件转成可审计、可再次展示的对话摘要文本。 */
@Component
@RequiredArgsConstructor
public class StreamMessageHistoryFormatter {

    private final ObjectMapper objectMapper;

    public String toHistoryText(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(chunk);
            String type = node.path("type").asText("");
            if (StreamMessageTypeEnum.AI_RESPONSE.getValue().equals(type)) {
                return node.path("data").asText("");
            }
            if (StreamMessageTypeEnum.THINKING.getValue().equals(type)) {
                // 不把内部思考内容写进历史；工具和最终答复仍然完整保留。
                return "";
            }
            if (StreamMessageTypeEnum.TOOL_REQUEST.getValue().equals(type)) {
                return "\n\n[选择工具] " + node.path("displayName").asText("未知工具") + "\n";
            }
            if (StreamMessageTypeEnum.TOOL_EXECUTED.getValue().equals(type)) {
                String displayName = node.path("displayName").asText("未知工具");
                String arguments = node.path("arguments").asText("{}");
                String result = node.path("result").asText("");
                return "\n\n[工具调用] " + displayName + " " + arguments + "\n" + result + "\n";
            }
        } catch (Exception ignored) {
            // 兼容 HTML/MULTI 文本流和未来的非 JSON 文本事件。
        }
        return chunk;
    }
}
