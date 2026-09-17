package com.lian.aicode.ai.model;

import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

/** AI 生成的 HTML、CSS、JavaScript 三文件结果。 */
@Description("生成原生多文件网页的结果，包含 index.html、style.css 和 script.js 的内容。")
@Data
public class MultiFileCodeResult {

    @Description("index.html 的完整内容；必须包含对 style.css 和 script.js 的相对路径引用")
    private String htmlCode;

    @Description("style.css 的完整内容；没有样式时返回空字符串")
    private String cssCode;

    @Description("script.js 的完整内容；没有交互时返回空字符串")
    private String jsCode;

    @Description("对页面功能和文件职责的简短说明")
    private String description;
}
