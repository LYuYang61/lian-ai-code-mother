package com.lian.aicode.model.enums;

import lombok.Getter;

/** 生成任务状态，数据库值保持稳定，便于前端和后续队列化处理。 */
@Getter
public enum AppGenerationStatusEnum {

    DRAFT("draft", "待生成"),
    GENERATING("generating", "生成中"),
    READY("ready", "已完成"),
    FAILED("failed", "失败"),
    CANCELLED("cancelled", "已取消");

    private final String value;
    private final String text;

    AppGenerationStatusEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }
}
