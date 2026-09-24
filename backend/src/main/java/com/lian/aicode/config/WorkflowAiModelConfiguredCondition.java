package com.lian.aicode.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * 只有主聊天模型的 API Key 非空时才创建工作流 AI Service。
 *
 * <p>不能用 @ConditionalOnBean(name = "openAiChatModel")：starter 自动配置的 bean 注册
 * 晚于用户 @Configuration 的条件评估，条件永远为 false（2026-09-24 实测图片计划与
 * 质检服务静默未装配）。按环境属性判断与 {@code RoutingAiModelConfiguredCondition}
 * 同一模式，不依赖 bean 注册顺序。</p>
 */
public class WorkflowAiModelConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String apiKey = context.getEnvironment().getProperty(
                "langchain4j.open-ai.chat-model.api-key");
        return StringUtils.hasText(apiKey);
    }
}
