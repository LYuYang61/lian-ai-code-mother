package com.lian.aicode.ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 验证路由模型有配置时能装配，测试不会发起真实请求。 */
@SpringBootTest(properties = {
        "langchain4j.open-ai.routing-chat-model.api-key=test-routing-key",
        "langchain4j.open-ai.routing-chat-model.base-url=https://example.invalid/v1",
        "langchain4j.open-ai.routing-chat-model.model-name=test-model",
        "langchain4j.open-ai.chat-model.api-key=test-key",
        "langchain4j.open-ai.streaming-chat-model.api-key=test-key"
})
@ActiveProfiles("test")
class CodeGenTypeRoutingConfigurationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void createsRoutingServiceWhenRoutingModelIsConfigured() {
        assertNotNull(applicationContext.getBean(AiCodeGenTypeRoutingService.class));
    }
}
