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

    /**
     * 剥离历史文本中的工具调用标记行，只保留模型叙述和工具结果摘要。
     *
     * <p>工具事件按 {@link #toHistoryText} 的格式生成独立的标记行（[选择工具] / [工具调用] 开头），
     * 其后紧跟结果行。展示层需要完整记录，但恢复进 ChatMemory 的历史如果带着这些标记行，
     * 会对模型形成强示范，诱导它在正文里伪造同样格式的工具记录而不发起真实调用
     * （2026-09-24 实测复现，被零变更守卫拦截）。因此按行移除两个标记行本身；
     * 不带标记的结果摘要（如"文件修改成功：src/App.vue"）与模型叙述保留。
     * 若剥离后没有剩余内容，返回占位说明，保持消息仍然存在。</p>
     */
    public static String stripToolTranscript(String historyText) {
        if (historyText == null || historyText.isBlank()) {
            return historyText == null ? "" : historyText;
        }
        StringBuilder kept = new StringBuilder();
        for (String line : historyText.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("[选择工具]") || trimmed.startsWith("[工具调用]")) {
                continue;
            }
            kept.append(line).append('\n');
        }
        String result = kept.toString().stripTrailing();
        return result.isBlank() ? "（本轮工具操作过程已省略，以对话双方文字内容为准）" : result;
    }
}
