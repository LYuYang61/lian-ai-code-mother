package com.lian.aicode.ai;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统提示词是行为安全约束的载体：历史里的工具调用摘要会被模型当作范文，
 * 2026-09-22 实测出现过模型用文本"模拟"工具调用的退化。此测试防止后续改写提示词时
 * 无意删掉禁止模拟工具调用的约束。
 */
class VueProjectSystemPromptTest {

    @Test
    void promptForbidsSimulatingToolCallsInText() throws IOException {
        String prompt = new ClassPathResource("prompt/codegen-vue-project-system-prompt.txt")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(prompt.contains("不能在回复文本中模拟、书写或复述工具调用记录"),
                "工程约束必须包含禁止文本模拟工具调用");
        assertTrue(prompt.contains("每一轮任务都必须在本轮重新发起真实工具调用"),
                "必须声明历史工具记录只是展示摘要，不能代表已执行");
        assertTrue(prompt.contains("回复中不得出现 [选择工具]、[工具调用]"),
                "输出约束必须禁止工具记录样式的文本");
    }
}
