package com.lian.aicode.core.parser;

import com.lian.aicode.ai.model.HtmlCodeResult;
import com.lian.aicode.ai.model.MultiFileCodeResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
