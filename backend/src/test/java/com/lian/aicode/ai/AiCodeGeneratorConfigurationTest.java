package com.lian.aicode.ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 只验证 starter 和 AI Service 的装配，不会调用真实模型接口。 */
@SpringBootTest(properties = {
        "langchain4j.open-ai.chat-model.api-key=test-key",
        "langchain4j.open-ai.streaming-chat-model.api-key=test-key"
})
class AiCodeGeneratorConfigurationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void createsAiServiceWhenBothModelBeansAreConfigured() {
        assertNotNull(applicationContext.getBean(AiCodeGeneratorService.class));
    }
}
