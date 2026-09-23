package com.lian.aicode.ai;

import com.lian.aicode.config.RoutingAiModelConfiguredCondition;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 创建独立的智能路由代理；没有路由密钥时由业务层使用确定性回退策略。 */
@Slf4j
@Configuration
@Conditional(RoutingAiModelConfiguredCondition.class)
public class AiCodeGenTypeRoutingServiceFactory {

    @Bean
    public AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService(
            @Qualifier("routingChatModelPrototype") ChatModel routingChatModel) {
        AiCodeGenTypeRoutingService delegate = AiServices.builder(AiCodeGenTypeRoutingService.class)
                .chatModel(routingChatModel)
                .build();
        log.info("初始化 AI 代码生成类型路由服务：modelConfigured=true, result=成功");
        return new LoggingRoutingService(delegate);
    }

    @Slf4j
    private static final class LoggingRoutingService implements AiCodeGenTypeRoutingService {

        private final AiCodeGenTypeRoutingService delegate;

        private LoggingRoutingService(AiCodeGenTypeRoutingService delegate) {
            this.delegate = delegate;
        }

        @Override
        public com.lian.aicode.model.enums.CodeGenTypeEnum routeCodeGenType(String userPrompt) {
            long startedAt = System.nanoTime();
            log.info("AI 代码类型路由开始：promptLength={}", userPrompt == null ? 0 : userPrompt.length());
            try {
                var result = delegate.routeCodeGenType(userPrompt);
                if (result == null) {
                    throw new IllegalStateException("路由模型返回空类型");
                }
                log.info("AI 代码类型路由结束：type={}, result=成功, durationMs={}",
                        result.getValue(), elapsedMillis(startedAt));
                return result;
            } catch (RuntimeException exception) {
                log.warn("AI 代码类型路由失败：reason={}, durationMs={}",
                        exception.getClass().getSimpleName(), elapsedMillis(startedAt));
                throw exception;
            }
        }

        private long elapsedMillis(long startedAt) {
            return (System.nanoTime() - startedAt) / 1_000_000;
        }
    }
}
