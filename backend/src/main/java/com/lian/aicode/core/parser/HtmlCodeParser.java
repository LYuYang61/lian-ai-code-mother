package com.lian.aicode.core.parser;

import com.lian.aicode.ai.model.HtmlCodeResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析单 HTML 代码块；没有代码围栏时兼容模型直接返回完整 HTML 的情况。 */
public class HtmlCodeParser implements CodeParser<HtmlCodeResult> {

    private static final Pattern HTML_CODE_PATTERN = Pattern.compile(
            "```[\\t ]*html[\\t ]*\\R(.*?)```", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public HtmlCodeResult parseCode(String codeContent) {
        HtmlCodeResult result = new HtmlCodeResult();
        if (codeContent == null) {
            return result;
        }
        String htmlCode = extractHtmlCode(codeContent);
        result.setHtmlCode((htmlCode == null ? codeContent : htmlCode).strip());
        return result;
    }

    private String extractHtmlCode(String content) {
        Matcher matcher = HTML_CODE_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }
}
