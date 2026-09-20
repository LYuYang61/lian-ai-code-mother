package com.lian.aicode.model.enums;

import lombok.Getter;

/** 应用是否允许出现在公开精选列表中。 */
@Getter
public enum AppVisibilityEnum {

    PRIVATE("private", "私有"),
    PUBLIC("public", "公开");

    private final String value;
    private final String text;

    AppVisibilityEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }

    public static boolean isValid(String value) {
        for (AppVisibilityEnum item : values()) {
            if (item.value.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
