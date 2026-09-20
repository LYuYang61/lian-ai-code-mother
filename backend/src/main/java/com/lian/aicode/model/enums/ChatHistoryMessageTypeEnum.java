package com.lian.aicode.model.enums;

import lombok.Getter;

/** 对话记录来源。AI 记录只在完整生成成功后写入，避免把半截代码误当成可用版本。 */
@Getter
public enum ChatHistoryMessageTypeEnum {

    USER("user", "用户"),
    AI("ai", "AI");

    private final String value;
    private final String text;

    ChatHistoryMessageTypeEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }
}
