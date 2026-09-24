package com.lian.aicode.workflow.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import com.lian.aicode.config.WorkflowAiModelConfiguredCondition;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工作流专用 AI Service 工厂。
 *
 * <p>通过 openAiChatModel 条件化创建；没有 API Key 时工作流仍能启动，节点会按配置跳过可选
 * 的计划和质检调用，而不是在 Spring 启动阶段因缺少密钥失败。</p>
 */
@Slf4j
@Configuration
@Conditional(WorkflowAiModelConfiguredCondition.class)
public class WorkflowAiServiceFactory {

    @Bean
    public ImageCollectionPlanService imageCollectionPlanService(
            @Qualifier("openAiChatModel") ChatModel chatModel) {
        ImageCollectionPlanService service = AiServices.builder(ImageCollectionPlanService.class)
                .chatModel(chatModel)
                .build();
        log.info("初始化 AI 工作流图片计划服务：result=成功");
        return service;
    }

    @Bean
    public CodeQualityCheckService codeQualityCheckService(
            @Qualifier("openAiChatModel") ChatModel chatModel) {
        CodeQualityCheckService service = AiServices.builder(CodeQualityCheckService.class)
                .chatModel(chatModel)
                .build();
        log.info("初始化 AI 工作流代码质检服务：result=成功");
        return service;
    }
}
