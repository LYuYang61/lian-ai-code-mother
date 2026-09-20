package com.lian.aicode.model.enums;

import lombok.Getter;

/** 单个代码版本的落盘状态。失败版本保留元数据，便于诊断但不允许预览。 */
@Getter
public enum AppVersionStatusEnum {

    GENERATING("generating", "生成中"),
    READY("ready", "可用"),
    FAILED("failed", "失败"),
    CANCELLED("cancelled", "已取消");

    private final String value;
    private final String text;

    AppVersionStatusEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }
}
