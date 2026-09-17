package com.lian.aicode.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 创建 AI Service 的适配层。
 *
 * <p>只有同步和流式模型都由 starter 成功创建后才注册业务服务。这样没有配置 API Key 时，
 * 项目仍可启动并运行本地解析器/保存器测试，不会在测试阶段误发外部请求。</p>
 */
@Configuration
@ConditionalOnProperty(name = {
        "langchain4j.open-ai.chat-model.api-key",
        "langchain4j.open-ai.streaming-chat-model.api-key"
})
public class AiCodeGeneratorServiceFactory {

    @Bean
    public AiCodeGeneratorService aiCodeGeneratorService(ChatModel chatModel,
                                                          StreamingChatModel streamingChatModel) {
        return AiServices.builder(AiCodeGeneratorService.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .build();
    }
}
