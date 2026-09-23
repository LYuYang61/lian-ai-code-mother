package com.lian.aicode.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/** 只有路由模型的 API Key 非空时才创建独立 ChatModel。 */
public class RoutingAiModelConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String apiKey = context.getEnvironment().getProperty(
                "langchain4j.open-ai.routing-chat-model.api-key");
        return StringUtils.hasText(apiKey);
    }
}
