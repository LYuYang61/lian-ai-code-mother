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

    /**
     * 2026-09-25 真机验收样本：模型未被要求结构化输出，却自发返回 ```json 围栏包裹的
     * {"htmlCode": ..., "description": ...}。解析器必须提取 htmlCode 字段（JSON 反转义后），
     * 而不是把整段原始输出落盘成 index.html。
     */
    @Test
    void parsesJsonFencedOutputByExtractingHtmlCodeField() {
        String content = """
                ```json
                {
                  "htmlCode": "<!DOCTYPE html>\\n<html lang=\\"zh-CN\\">\\n<body><h1>原子结构演示</h1></body></html>",
                  "description": "一个可交互的原子结构教学页面"
                }
                ```
                """;

        HtmlCodeResult result = htmlCodeParser.parseCode(content);

        assertEquals("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<body><h1>原子结构演示</h1></body></html>",
                result.getHtmlCode());
        assertEquals("一个可交互的原子结构教学页面", result.getDescription());
    }

    @Test
    void parsesBareJsonObjectWithoutFence() {
        String content = "{\"htmlCode\": \"<html><body>ok</body></html>\", \"description\": \"说明\"}";

        HtmlCodeResult result = htmlCodeParser.parseCode(content);

        assertEquals("<html><body>ok</body></html>", result.getHtmlCode());
        assertEquals("说明", result.getDescription());
    }

    /**
     * 围栏内是非法 JSON 时必须按生成失败拒绝：此类内容以 ```json 开头，
     * 绝不能走"原始输出兜底落盘"路径变成可用版本。
     */
    @Test
    void rejectsInvalidJsonFenceInsteadOfSavingRawOutput() {
        String content = """
                ```json
                {"htmlCode": "<!DOCTYPE html><html><body>半截</html>",  描述没有闭合
                ```
                """;

        assertThrows(BusinessException.class, () -> htmlCodeParser.parseCode(content));
    }

    /**
     * 完整性守卫的形态检查：正文说明里 merely 出现 </html> 字面量不能算完整文档，
     * 最终内容必须以 <!DOCTYPE 或 <html 开头，否则拒绝保存。
     */
    @Test
    void rejectsProseThatMerelyContainsClosingTag() {
        assertThrows(BusinessException.class, () -> htmlCodeParser.parseCode(
                "这是一段说明文字，中间出现了 </html> 字样，但它不是 HTML 文档"));
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
