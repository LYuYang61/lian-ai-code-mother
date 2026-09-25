package com.lian.aicode.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 摘要提示词的定位守护测试。
 *
 * <p>2026-09-25 真机发现（幽灵指令事故的同族风险）：旧摘要提示词要求保留"未解决问题"
 * 和"下一轮 AI 生成最需要知道的上下文"，使摘要成为一份持久化的待办清单——其中"未解决"
 * 项会随时间过期（摘要按 20 条消息增量才刷新），后续轮次若用户指令模糊，模型可能凭摘要
 * 捡起过期待办。v15 实测免责声明有效，但提示词本身必须把摘要定位为"事实快照"而非
 * "可继续执行的交接"：未见实现的需求只作历史状态记录，禁止阅读者据此主动实施。</p>
 */
class ChatSummaryPromptGuardTest {

    @Test
    void summaryPromptFramesPendingItemsAsFactsNotTodos() throws IOException {
        String prompt = readResource("prompt/chat-summary-system-prompt.txt");

        assertTrue(prompt.contains("不是待办清单"), "摘要必须明确定位为事实快照而非待办清单");
        assertTrue(prompt.contains("不得仅凭摘要主动实施"),
                "必须禁止阅读者仅凭摘要主动实施任何事项");
        assertTrue(prompt.contains("不要补充对话中没有出现"),
                "摘要不得补充对话之外的设计或修复建议（真机样本中摘要模型曾自行补'建议改用青蓝色'）");
    }

    @Test
    void summaryPromptMustNotCarryNextRoundInstructionWording() throws IOException {
        String prompt = readResource("prompt/chat-summary-system-prompt.txt");

        assertFalse(prompt.contains("下一轮"), "不得保留面向下一轮的指令性措辞：" + prompt);
        assertFalse(prompt.contains("未解决问题"), "待办式标题不得复现，改用事实性分类（已实现/已取消/仍未见实现）");
        assertFalse(prompt.contains("可继续执行"), "摘要不得被描述为可继续执行的任务");
    }

    private String readResource(String path) throws IOException {
        try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            assertTrue(stream != null, "提示词资源应存在于 classpath：" + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
