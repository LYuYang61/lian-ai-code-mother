package com.lian.aicode.workflow.model;

import lombok.Getter;

/** 工作流素材分类；分类值会进入提示词，不直接作为文件路径使用。 */
@Getter
public enum ImageCategoryEnum {
    CONTENT("内容图片"),
    ILLUSTRATION("装饰插画"),
    ARCHITECTURE("架构图"),
    LOGO("品牌 Logo");

    private final String text;

    ImageCategoryEnum(String text) {
        this.text = text;
    }
}
