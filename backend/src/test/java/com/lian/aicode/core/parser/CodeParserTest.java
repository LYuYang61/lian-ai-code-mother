package com.lian.aicode.core.parser;

import com.lian.aicode.ai.model.HtmlCodeResult;
import com.lian.aicode.ai.model.MultiFileCodeResult;
import com.lian.aicode.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodeParserTest {

    private final HtmlCodeParser htmlCodeParser = new HtmlCodeParser();
    private final MultiFileCodeParser multiFileCodeParser = new MultiFileCodeParser();

    @Test
    void parsesHtmlFenceAndIgnoresExplanation() {
        String content = """
                页面说明
                ```html
                <!doctype html>
                <html><body><h1>Hello</h1></body></html>
                ```
                说明结束
                """;

        HtmlCodeResult result = htmlCodeParser.parseCode(content);

        assertNotNull(result);
        assertEquals("<!doctype html>\n<html><body><h1>Hello</h1></body></html>", result.getHtmlCode());
    }

    @Test
    void fallsBackToCompleteHtmlWhenNoFenceExists() {
        HtmlCodeResult result = htmlCodeParser.parseCode("  <html><body>ok</body></html>  ");

        assertEquals("<html><body>ok</body></html>", result.getHtmlCode());
    }

    @Test
    void parsesThreeFilesAndSupportsJavascriptLabel() {
        String content = """
                ```html filename=index.html
                <!doctype html>
                <html><body><h1>Demo</h1></body></html>
                ```
                ```css filename=style.css
                body { color: navy; }
                ```
                ```javascript filename=script.js
                console.log('ready');
                ```
                """;

        MultiFileCodeResult result = multiFileCodeParser.parseCode(content);

        assertEquals("<!doctype html>\n<html><body><h1>Demo</h1></body></html>", result.getHtmlCode());
        assertEquals("body { color: navy; }", result.getCssCode());
        assertEquals("console.log('ready');", result.getJsCode());
    }

    /**
     * 2026-09-20 真机验收样本：模型输出被截断，只有开头围栏，没有闭合围栏，
     * 内容停在 CSS 中间。解析器必须按生成失败拒绝，不能把说明文字和围栏
     * 一起当作 HTML 保存成“可用版本”。
     */
    @Test
    void rejectsTruncatedFencedOutputInsteadOfReturningDirtyHtml() {
        String truncated = """
                这是一个可直接运行的待办事项清单网页，支持添加、勾选、删除和筛选等操作。
                ```html
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <style>
                  body { color: var(--text);
                """;

        assertThrows(BusinessException.class, () -> htmlCodeParser.parseCode(truncated));
    }

    @Test
    void rejectsUnfencedHtmlWithoutClosingTag() {
        assertThrows(BusinessException.class,
                () -> htmlCodeParser.parseCode("<html><body><h1>半截"));
    }

    @Test
    void rejectsMultiFileHtmlBlockWithoutClosingTag() {
        String content = """
                ```html
                <html><body><h1>半截
                ```
                ```css
                body { color: navy; }
                ```
                """;

        assertThrows(BusinessException.class, () -> multiFileCodeParser.parseCode(content));
    }
}
