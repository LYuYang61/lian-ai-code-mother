package com.lian.aicode.ai.model;

import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

/**
 * AI 生成的对话摘要结果。
 *
 * <p>摘要也使用结构化对象而不是 String 返回：当前 chat-model 全局启用了
 * {@code response_format=json_object}，DeepSeek 要求该模式下提示词必须包含 json 字样；
 * 结构化返回会让 LangChain4j 自动注入 JSON 输出说明，避免摘要请求被服务端拒绝。</p>
 */
@Description("历史对话的压缩摘要结果")
@Data
public class ConversationSummaryResult {

    @Description("压缩后的中文对话摘要，保留用户目标、已完成版本与未解决问题")
    private String summary;
}
