package com.lian.aicode.ai.guardrail;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Vue 工程生成的输入护轨。
 *
 * <p>它只拦截明显的提示注入语句和超长输入，不能替代文件工具的路径校验、构建沙箱或
 * 权限校验。护轨不记录原始提示词，避免日志泄露用户内容。</p>
 */
public final class PromptSafetyInputGuardrail implements InputGuardrail {

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            // 修饰词允许重复出现，覆盖 "ignore all previous instructions" 这类多词间隔的变体。
            Pattern.compile("(?i)ignore\\s+(?:(?:previous|above|all|prior)\\s+)+(?:instructions?|commands?|prompts?)"),
            Pattern.compile("(?i)(?:forget|disregard)\\s+(?:(?:everything|all|previous)\\s+)+(?:above|before|instructions?|prompts?)"),
            Pattern.compile("(?i)system\\s*:\\s*you\\s+are"),
            Pattern.compile("(?i)new\\s+(?:instructions?|commands?|prompts?)\\s*:")
    );

    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {
        String input = userMessage == null ? "" : userMessage.singleText();
        if (input == null || input.isBlank()) {
            return fatal("输入内容不能为空");
        }
        if (input.length() > 10_000) {
            return fatal("输入内容过长");
        }
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(input).find()) {
                return fatal("检测到疑似提示注入，请仅描述希望生成的网页功能");
            }
        }
        return success();
    }
}
