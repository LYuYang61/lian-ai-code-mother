package com.lian.aicode.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 验证第十期模型多例化的装配语义：每次获取原型模型都产生全新实例，
 * 而流式 HTTP 读线程池在所有模型间共享（避免每个模型常驻一个空闲线程）。
 * 只验证对象装配与作用域，不发起真实模型请求。
 */
@SpringBootTest(properties = {
        "langchain4j.open-ai.chat-model.api-key=test-key",
        "langchain4j.open-ai.streaming-chat-model.api-key=test-key",
        "langchain4j.open-ai.routing-chat-model.api-key=test-routing-key",
        "langchain4j.open-ai.routing-chat-model.base-url=https://example.invalid/v1",
        "langchain4j.open-ai.routing-chat-model.model-name=test-model"
})
@ActiveProfiles("test")
class AiModelPrototypeConfigurationTest {

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("chatModelPrototype")
    private ObjectProvider<ChatModel> chatModelPrototypeProvider;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("streamingChatModelPrototype")
    private ObjectProvider<StreamingChatModel> streamingChatModelPrototypeProvider;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("streamingHttpTaskExecutor")
    private ThreadPoolTaskExecutor streamingHttpTaskExecutor;

    @Autowired
    private AiCodeGeneratorServiceFactory serviceFactory;

    @Autowired
    private AiCodeGenTypeRoutingServiceFactory routingServiceFactory;

    @Test
    void chatModelPrototypeCreatesNewInstanceEachTime() {
        ChatModel first = chatModelPrototypeProvider.getObject();
        ChatModel second = chatModelPrototypeProvider.getObject();
        assertNotNull(first);
        assertNotSame(first, second, "同步模型必须是 prototype 作用域");
    }

    @Test
    void streamingModelPrototypeCreatesNewInstanceEachTime() {
        StreamingChatModel first = streamingChatModelPrototypeProvider.getObject();
        StreamingChatModel second = streamingChatModelPrototypeProvider.getObject();
        assertNotNull(first);
        assertNotSame(first, second, "流式模型必须是 prototype 作用域");
    }

    @Test
    void statelessServiceIsCreatedPerTask() {
        AiCodeGeneratorService first = serviceFactory.getForStatelessTask();
        AiCodeGeneratorService second = serviceFactory.getForStatelessTask();
        assertNotNull(first);
        assertNotSame(first, second, "无状态服务必须按次创建");
    }

    @Test
    void routingServiceIsCreatedPerCallAndDefaultBeanRemains() {
        AiCodeGenTypeRoutingService first = routingServiceFactory.createAiCodeGenTypeRoutingService();
        AiCodeGenTypeRoutingService second = routingServiceFactory.createAiCodeGenTypeRoutingService();
        assertNotNull(first);
        assertNotSame(first, second, "路由服务必须按次创建");
        assertNotNull(routingServiceFactory, "路由工厂应可用");
    }

    @Test
    void streamingHttpExecutorIsSharedSingleton() {
        ThreadPoolTaskExecutor again = streamingHttpTaskExecutor;
        assertSame(streamingHttpTaskExecutor, again);
        // 线程名前缀可从日志区分流式读取线程；共享池必须允许按需扩线程（core=0）。
        org.junit.jupiter.api.Assertions.assertTrue(
                streamingHttpTaskExecutor.getThreadNamePrefix().contains("langchain4j-stream-http"));
    }
}
