package com.lian.aicode.core.stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史文本剥离工具摘要的判据测试。2026-09-24 实测：带着 [选择工具]/[工具调用]
 * 展示段落恢复记忆，模型会在正文里伪造工具记录而不发起真实调用；恢复进 ChatMemory
 * 的 AI 历史必须只保留自然语言叙述。
 */
class StreamMessageHistoryFormatterStripTest {

    /** 取自 2026-09-24 电商管理系统 v2 真实历史（Redis 记忆原文格式）。 */
    private static final String REAL_HISTORY = """
            I'll read the current files first to find the homepage title.

            [选择工具] 读取文件


            [工具调用] 读取文件 {"relativeFilePath":"src/pages/HomePage.vue"}
            文件读取完成，内容未展示

            [工具调用] 修改文件 {"relativeFilePath":"src/pages/HomePage.vue"}
            文件修改成功：src/pages/HomePage.vue 已完成：将首页标题改为"奶龙电商后台"。""";

    @Test
    void stripsToolTranscriptParagraphsButKeepsNarrative() {
        String stripped = StreamMessageHistoryFormatter.stripToolTranscript(REAL_HISTORY);

        assertTrue(stripped.startsWith("I'll read the current files first"),
                "模型叙述必须保留");
        assertTrue(stripped.contains("已完成：将首页标题改为"), "完成说明必须保留");
        assertTrue(stripped.contains("文件修改成功：src/pages/HomePage.vue"),
                "不带标记的工具结果摘要保留，它是无害的普通文本");
        assertFalse(stripped.contains("[选择工具]"), "选择工具标记行必须剥离");
        assertFalse(stripped.contains("[工具调用]"), "工具调用标记行必须剥离——伪造复刻的正是这种格式");
    }

    @Test
    void keepsPlainTextHistoryUnchanged() {
        assertEquals("已按需求完成修改，共更新两个文件。",
                StreamMessageHistoryFormatter.stripToolTranscript("已按需求完成修改，共更新两个文件。"));
        assertEquals("第一行\n第二行",
                StreamMessageHistoryFormatter.stripToolTranscript("第一行\n第二行"));
    }

    @Test
    void pureToolTranscriptFallsBackToPlaceholder() {
        String stripped = StreamMessageHistoryFormatter.stripToolTranscript("""
                [选择工具] 读取目录
                [工具调用] 读取目录 {"relativeDirPath":""}""");
        assertEquals("（本轮工具操作过程已省略，以对话双方文字内容为准）", stripped);
    }

    @Test
    void nullAndBlankAreSafe() {
        assertEquals("", StreamMessageHistoryFormatter.stripToolTranscript(null));
        assertEquals("  ", StreamMessageHistoryFormatter.stripToolTranscript("  "));
    }
}
