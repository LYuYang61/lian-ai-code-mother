package com.lian.aicode.model.enums;

import lombok.Getter;

/** 用户角色。管理员角色只能由管理员在数据库或后台授予，不能通过公开注册创建。 */
@Getter
public enum UserRoleEnum {

    USER("user", "普通用户"),
    ADMIN("admin", "管理员");

    private final String value;
    private final String text;

    UserRoleEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }

    public static boolean isAdmin(String value) {
        return ADMIN.value.equals(value);
    }
}
