package com.lian.aicode.ai;

import com.lian.aicode.config.RoutingAiModelConfiguredCondition;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * 智能路由服务工厂：每次调用 {@link #createAiCodeGenTypeRoutingService()} 都用全新的
 * prototype 路由模型创建独立服务实例，支持创建应用入口的并发路由（教程 11 期同款思路）。
 *
 * <p>创建出的服务按“缓存 → 日志 → 模型”顺序包装：路由结论命中
 * {@link CodeGenRouteCache} 时不再发起模型调用；没有路由密钥时整个工厂不装配，
 * 业务层使用确定性回退策略。</p>
 */
@Slf4j
@Configuration
@Conditional(RoutingAiModelConfiguredCondition.class)
public class AiCodeGenTypeRoutingServiceFactory {

    private final ObjectProvider<ChatModel> routingChatModelPrototypeProvider;
    private final CodeGenRouteCache routeCache;

    public AiCodeGenTypeRoutingServiceFactory(
            @Qualifier("routingChatModelPrototype") ObjectProvider<ChatModel> routingChatModelPrototypeProvider,
            CodeGenRouteCache routeCache) {
        this.routingChatModelPrototypeProvider = routingChatModelPrototypeProvider;
        this.routeCache = routeCache;
    }

    /**
     * 兼容保留的默认单例服务；生产调用方应使用 {@link #createAiCodeGenTypeRoutingService()}
     * 按次获取，避免并发路由共享同一个模型实例。
     */
    @Bean
    public AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService() {
        return createAiCodeGenTypeRoutingService();
    }

    /** 每次调用创建新的路由服务：prototype 模型 + 日志包装 + 结果缓存。 */
    public AiCodeGenTypeRoutingService createAiCodeGenTypeRoutingService() {
        ChatModel routingChatModel = routingChatModelPrototypeProvider.getObject();
        AiCodeGenTypeRoutingService delegate = AiServices.builder(AiCodeGenTypeRoutingService.class)
                .chatModel(routingChatModel)
                .build();
        log.info("创建智能路由服务实例：modelScope=prototype, result=成功");
        return new CachedRoutingService(routeCache, new LoggingRoutingService(delegate));
    }

    /** 路由结果缓存层：相同需求描述在 TTL 内直接复用上次结论，减少模型调用。 */
    @Slf4j
    private static final class CachedRoutingService implements AiCodeGenTypeRoutingService {

        private final CodeGenRouteCache cache;
        private final AiCodeGenTypeRoutingService delegate;

        private CachedRoutingService(CodeGenRouteCache cache, AiCodeGenTypeRoutingService delegate) {
            this.cache = cache;
            this.delegate = delegate;
        }

        @Override
        public com.lian.aicode.model.enums.CodeGenTypeEnum routeCodeGenType(String userPrompt) {
            com.lian.aicode.model.enums.CodeGenTypeEnum cached = cache.get(userPrompt);
            if (cached != null) {
                return cached;
            }
            com.lian.aicode.model.enums.CodeGenTypeEnum result = delegate.routeCodeGenType(userPrompt);
            if (result != null) {
                cache.put(userPrompt, result);
            }
            return result;
        }
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
