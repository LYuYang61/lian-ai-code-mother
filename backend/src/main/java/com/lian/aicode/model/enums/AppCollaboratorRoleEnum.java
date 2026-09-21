package com.lian.aicode.model.enums;

import lombok.Getter;

/** 协作者权限；viewer 只能查看，editor 可以继续发起 AI 生成。 */
@Getter
public enum AppCollaboratorRoleEnum {

    VIEWER("viewer", "查看者"),
    EDITOR("editor", "编辑者");

    private final String value;
    private final String text;

    AppCollaboratorRoleEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }

    public static AppCollaboratorRoleEnum fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (AppCollaboratorRoleEnum role : values()) {
            if (role.value.equalsIgnoreCase(value.trim())) {
                return role;
            }
        }
        return null;
    }
}
