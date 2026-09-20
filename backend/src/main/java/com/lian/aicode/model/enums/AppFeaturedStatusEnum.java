package com.lian.aicode.model.enums;

import lombok.Getter;

/** 精选申请状态，避免普通用户直接篡改 priority。 */
@Getter
public enum AppFeaturedStatusEnum {

    NONE("none", "未申请"),
    PENDING("pending", "审核中"),
    APPROVED("approved", "已精选"),
    REJECTED("rejected", "已拒绝");

    private final String value;
    private final String text;

    AppFeaturedStatusEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }
}
