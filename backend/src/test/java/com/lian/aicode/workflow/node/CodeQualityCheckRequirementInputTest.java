package com.lian.aicode.workflow.node;

import com.lian.aicode.workflow.model.WorkflowContext;
import com.lian.aicode.workflow.service.WorkflowCodeReader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 质检输入必须携带本轮用户需求。
 *
 * <p>2026-09-25 真机事故：v13 轮模型被残留的"幽灵指令"带偏，只做了数据看板页而完全
 * 未实现真实需求；质检却返回 valid=true——因为质检系统提示词要求评估"是否满足用户需求
 * 中的核心交互"，而输入里只有变更代码文件、没有需求文本，质检模型无从判断偏题。
 * 修复后质检输入以"本轮用户需求"一节开头，使偏题检测真正可判定。</p>
 */
class CodeQualityCheckRequirementInputTest {

    @Test
    void qualityCheckInputContainsUserRequirementSection() {
        WorkflowContext context = new WorkflowContext();
        context.setOriginalPrompt("把页面主标题改成深蓝色，并在页脚追加一行版权说明文字");
        context.setEnhancedPrompt("把页面主标题改成深蓝色，并在页脚追加一行版权说明文字\n\n## 可用素材\n（素材不是需求）");

        String input = CodeQualityCheckNode.buildQualityInput(context,
                new WorkflowCodeReader.CodeSnapshot("<html><body>x</body></html>", 1));

        assertTrue(input.contains("本轮用户需求"), "质检输入必须包含需求标识节");
        assertTrue(input.contains("把页面主标题改成深蓝色"),
                "质检输入必须包含原始需求文本（增强提示里的素材段不是需求）");
        assertTrue(input.endsWith("<html><body>x</body></html>"), "代码内容保持在需求节之后");
        assertTrue(input.indexOf("本轮用户需求") < input.indexOf("<html>"), "需求节应位于输入开头，便于质检模型先看需求再看代码");
    }

    @Test
    void blankRequirementKeepsCodeOnlyInput() {
        WorkflowContext context = new WorkflowContext();

        String input = CodeQualityCheckNode.buildQualityInput(context,
                new WorkflowCodeReader.CodeSnapshot("<html><body>y</body></html>", 1));

        assertEquals("<html><body>y</body></html>", input, "无需求文本时不应拼出空需求节");
    }
}
