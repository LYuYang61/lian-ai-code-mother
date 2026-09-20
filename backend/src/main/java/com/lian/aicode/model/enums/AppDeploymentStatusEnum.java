package com.lian.aicode.model.enums;

import lombok.Getter;

/** 部署开关状态；停用只关闭公开访问，不删除可回滚的生成文件。 */
@Getter
public enum AppDeploymentStatusEnum {

    UNDEPLOYED("undeployed", "未部署"),
    DEPLOYED("deployed", "已部署"),
    PAUSED("paused", "已暂停");

    private final String value;
    private final String text;

    AppDeploymentStatusEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }
}
