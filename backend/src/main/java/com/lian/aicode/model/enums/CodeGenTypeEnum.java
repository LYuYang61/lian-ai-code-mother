package com.lian.aicode.model.enums;

import lombok.Getter;

import java.util.Arrays;

/** 网站代码生成模式。枚举值会参与数据库和前端协议，必须保持稳定。 */
@Getter
public enum CodeGenTypeEnum {

    HTML("原生 HTML 模式", "html"),
    MULTI_FILE("原生多文件模式", "multi_file"),
    VUE_PROJECT("Vue 工程模式", "vue_project");

    private final String text;
    private final String value;

    CodeGenTypeEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 按外部传入的稳定值查找枚举。
     *
     * @param value 生成类型值
     * @return 匹配的枚举；不存在或为空时返回 {@code null}
     */
    public static CodeGenTypeEnum getEnumByValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst()
                .orElse(null);
    }
}
