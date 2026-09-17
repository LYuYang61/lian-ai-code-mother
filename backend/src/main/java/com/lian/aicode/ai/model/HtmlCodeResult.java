package com.lian.aicode.ai.model;

import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

/** AI 生成的单文件网页结果。 */
@Description("生成 HTML 代码文件的结果。htmlCode 必须是完整、可直接打开的 HTML 文档。")
@Data
public class HtmlCodeResult {

    @Description("完整的 HTML 代码；不要包含 Markdown 代码围栏")
    private String htmlCode;

    @Description("对页面功能和实现的简短说明")
    private String description;
}
