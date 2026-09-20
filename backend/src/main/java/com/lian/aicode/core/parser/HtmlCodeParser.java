package com.lian.aicode.core.parser;

import com.lian.aicode.ai.model.HtmlCodeResult;
import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析单 HTML 代码块；没有代码围栏时兼容模型直接返回完整 HTML 的情况。 */
public class HtmlCodeParser implements CodeParser<HtmlCodeResult> {

    private static final Pattern HTML_CODE_PATTERN = Pattern.compile(
            "```[\\t ]*html[\\t ]*\\R(.*?)```", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * 完整 HTML 文档必然出现 body 或 html 的闭合标签。真机验收发现模型输出可能被截断
     * （只有开头围栏、内容停在样式中间），这类内容必须按生成失败拒绝，不能带着说明文字
     * 和围栏一起落盘成“可用版本”。提示词约束模型“只输出完整代码”是软约束，这里是硬保障。
     */
    private static final Pattern COMPLETE_HTML_PATTERN =
            Pattern.compile("<\\s*/\\s*(?:body|html)\\s*>", Pattern.CASE_INSENSITIVE);

    @Override
    public HtmlCodeResult parseCode(String codeContent) {
        HtmlCodeResult result = new HtmlCodeResult();
        if (codeContent == null) {
            return result;
        }
        String htmlCode = extractHtmlCode(codeContent);
        String finalCode = (htmlCode == null ? codeContent : htmlCode).strip();
        requireCompleteHtml(finalCode);
        result.setHtmlCode(finalCode);
        return result;
    }

    private String extractHtmlCode(String content) {
        Matcher matcher = HTML_CODE_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    private void requireCompleteHtml(String htmlCode) {
        if (htmlCode == null || htmlCode.isBlank() || !COMPLETE_HTML_PATTERN.matcher(htmlCode).find()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "生成内容不完整（HTML 文档未闭合），已拒绝保存，请重试或简化需求描述");
        }
    }
}
