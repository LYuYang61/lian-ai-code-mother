package com.lian.aicode.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * 主生成模型（同步调用）的原型（prototype）配置。
 *
 * <p>应用命名、对话摘要等同步模型调用按次从这里获取全新实例，避免多个并发请求共享同一个
 * {@code ChatModel}；字段集合必须与 application.yml 的 {@code langchain4j.open-ai.chat-model}
 * 保持一致（含 {@code response-format=json_object} 和重试次数），修改任一侧时要同步检查另一侧。
 * 与 {@link RoutingAiModelConfig} 相同的配置模式，仅绑定的前缀不同。</p>
 */
@Data
@Configuration
@ConditionalOnProperty(name = {
        "langchain4j.open-ai.chat-model.api-key",
        "langchain4j.open-ai.streaming-chat-model.api-key"
})
@ConfigurationProperties(prefix = "langchain4j.open-ai.chat-model")
public class GenerationChatModelConfig {

    private String baseUrl;
    private String apiKey;
    private String modelName;
    private Integer maxTokens;
    private Double temperature;
    private Integer maxRetries;
    private String responseFormat;
    private Boolean logRequests = false;
    private Boolean logResponses = false;

    @Bean
    @Scope("prototype")
    public ChatModel chatModelPrototype() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .maxRetries(maxRetries)
                .responseFormat(responseFormat)
                .logRequests(Boolean.TRUE.equals(logRequests))
                .logResponses(Boolean.TRUE.equals(logResponses))
                .build();
    }
}
