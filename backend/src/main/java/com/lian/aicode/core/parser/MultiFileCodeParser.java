package com.lian.aicode.core.parser;

import com.lian.aicode.ai.model.MultiFileCodeResult;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 HTML、CSS、JavaScript 三类 Markdown 代码块。
 *
 * <p>只识别固定语言标签或固定文件名，不把模型输出中的任意文本当作路径，避免把不可信内容
 * 扩展成可写文件名。</p>
 */
public class MultiFileCodeParser implements CodeParser<MultiFileCodeResult> {

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "```[\\t ]*([^\\r\\n]*)\\R(.*?)```", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public MultiFileCodeResult parseCode(String codeContent) {
        MultiFileCodeResult result = new MultiFileCodeResult();
        if (codeContent == null) {
            return result;
        }
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(codeContent);
        while (matcher.find()) {
            String label = matcher.group(1).strip().toLowerCase(Locale.ROOT);
            String content = matcher.group(2).strip();
            if (isHtmlLabel(label) && isBlank(result.getHtmlCode())) {
                result.setHtmlCode(content);
            } else if (isCssLabel(label) && isBlank(result.getCssCode())) {
                result.setCssCode(content);
            } else if (isJavaScriptLabel(label) && isBlank(result.getJsCode())) {
                result.setJsCode(content);
            }
        }
        return result;
    }

    private boolean isHtmlLabel(String label) {
        return label.startsWith("html") || label.contains("index.html");
    }

    private boolean isCssLabel(String label) {
        return label.startsWith("css") || label.contains("style.css");
    }

    private boolean isJavaScriptLabel(String label) {
        return label.startsWith("js")
                || label.startsWith("javascript")
                || label.contains("script.js");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
