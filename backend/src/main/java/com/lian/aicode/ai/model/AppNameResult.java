package com.lian.aicode.ai.model;

import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

/**
 * AI 生成的应用名称结果。
 *
 * <p>应用名称也采用结构化对象，而不是直接声明为 String。这样可以和当前全局 JSON
 * 输出模式保持一致，避免模型返回 JSON 对象时由字符串转换器解析失败。</p>
 */
@Description("应用卡片使用的简短中文名称")
@Data
public class AppNameResult {

    @Description("简短、清晰、适合展示在应用卡片上的中文名称，不超过 20 个汉字")
    private String appName;
}
