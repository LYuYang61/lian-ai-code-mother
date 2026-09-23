package com.lian.aicode.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * AI 代码生成类型路由模型。
 *
 * <p>路由使用独立模型配置且不继承主模型的 {@code response-format=json_object}，因为返回协议是
 * 一个枚举值。这样既保留主生成模型的结构化 JSON，又避免路由请求被 JSON 对象约束干扰。</p>
 */
@Data
@Configuration
@Conditional(RoutingAiModelConfiguredCondition.class)
@ConfigurationProperties(prefix = "langchain4j.open-ai.routing-chat-model")
public class RoutingAiModelConfig {

    private String baseUrl;
    private String apiKey;
    private String modelName;
    private Integer maxTokens = 128;
    private Double temperature = 0.0;
    private Boolean logRequests = false;
    private Boolean logResponses = false;

    @Bean
    @Scope("prototype")
    public ChatModel routingChatModelPrototype() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .logRequests(Boolean.TRUE.equals(logRequests))
                .logResponses(Boolean.TRUE.equals(logResponses))
                .build();
    }
}
