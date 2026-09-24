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
        assertTrue(prompt.contains("调用 modifyFile 前必须先用 readFile 读取同一文件"),
                "增量修改的先读后改约束必须进入创建提示词");
    }

    @Test
    void modificationPromptRequiresReadingBeforeIncrementalChanges() throws IOException {
        String prompt = new ClassPathResource("prompt/codegen-vue-project-modify-system-prompt.txt")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(prompt.contains("先调用 readDir"), "增量修改必须先了解真实工程结构");
        assertTrue(prompt.contains("优先使用 modifyFile"), "增量修改应优先使用精确替换工具");
        assertTrue(prompt.contains("拒绝空文本或多处匹配"), "增量修改必须避免模糊替换");
        assertTrue(prompt.contains("可视化编辑目标"), "增量修改提示词必须理解可视化定位上下文");
        assertTrue(prompt.contains("不要把完整源代码复制到最终说明中"),
                "最终说明不能回显完整工程源码");
        // 2026-09-24 实测：修改提示词首版未约束"第一响应必须是工具调用"，
        // 模型直接在正文伪造 [选择工具]/[工具调用] 记录并宣布完成，被零变更守卫拦截。
        assertTrue(prompt.contains("第一响应必须是一个真实工具调用"),
                "修改提示词必须禁止首响直接输出文本答复");
        assertTrue(prompt.contains("不能在回复文本中模拟、书写或复述工具调用记录"),
                "修改提示词必须包含与创建提示词同级的反伪造条款");
        assertTrue(prompt.contains("回复中不得出现 [选择工具]、[工具调用]"),
                "修改提示词输出约束必须禁止工具记录样式的文本");
    }
}
