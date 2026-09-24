package com.lian.aicode.workflow.model;

import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 图片收集计划。字段描述会进入 LangChain4j 的结构化输出约束，减少模型返回自由文本的概率。
 */
@Data
public class ImageCollectionPlan implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Description("需要从 Pexels 搜索的内容图片任务，通常是产品、场景或人物照片")
    private List<ImageSearchTask> contentImageTasks;

    @Description("需要从 unDraw 搜索的装饰插画任务")
    private List<IllustrationTask> illustrationTasks;

    @Description("需要使用 Mermaid CLI 生成架构图的任务，mermaidCode 必须是合法 Mermaid 源码")
    private List<DiagramTask> diagramTasks;

    @Description("需要使用阿里云 Model Studio 文生图生成 Logo 的任务")
    private List<LogoTask> logoTasks;

    public record ImageSearchTask(
            @Description("英文或中文搜索关键词") String query) implements Serializable {
    }

    public record IllustrationTask(
            @Description("插画搜索关键词") String query) implements Serializable {
    }

    public record DiagramTask(
            @Description("Mermaid 图表源码") String mermaidCode,
            @Description("图表用途说明") String description) implements Serializable {
    }

    public record LogoTask(
            @Description("Logo 的品牌、行业和视觉风格描述") String description) implements Serializable {
    }
}
