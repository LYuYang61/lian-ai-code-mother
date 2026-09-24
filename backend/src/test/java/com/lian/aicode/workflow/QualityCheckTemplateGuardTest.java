package com.lian.aicode.workflow;

import com.lian.aicode.workflow.ai.CodeQualityCheckService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 2026-09-24 真机失败回归：质检输入的前端代码含 Vue 插值 {{layer.tag}}，
 * 无注解的单 String @UserMessage 被 LangChain4j 当作提示词模板渲染，
 * 5ms 内抛 "Value for the variable 'layer.tag' is missing" 并被 fail-open 掩盖。
 * 修复后代码内容作为 @V 命名变量的值传入，模板引擎只扫描写死的模板本身。
 * 用不可达端点验证：模板渲染发生在网络请求前，若回归会先于连接异常抛出。
 */
class QualityCheckTemplateGuardTest {

    @Test
    void codeContentWithVueInterpolationMustNotBeParsedAsTemplate() {
        ChatModel model = OpenAiChatModel.builder()
                .baseUrl("http://127.0.0.1:9")
                .apiKey("fake-key")
                .modelName("deepseek-flash")
                .build();
        CodeQualityCheckService service = AiServices.builder(CodeQualityCheckService.class)
                .chatModel(model)
                .build();
        String code = "<template><span>{{ layer.tag }}</span>"
                + "<div v-for=\"item in list\">{{ item.label }}</div></template>";
        try {
            service.checkCodeQuality(code);
            fail("不可达端点应当抛出连接异常");
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("layer.tag")) {
                fail("代码内容仍被当作提示词模板渲染：" + exception.getMessage());
            }
            throw exception;
        } catch (RuntimeException expected) {
            // 连接被拒（ResourceAccessException 等）说明已通过模板渲染阶段。
            assertTrue(expected.getMessage() != null, "应携带连接错误信息");
        }
    }
}
