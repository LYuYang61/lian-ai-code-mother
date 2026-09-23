package com.lian.aicode.model.dto.app;

import com.lian.aicode.model.enums.AppVisibilityEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 创建应用请求；代码生成类型为空或为 auto 时由服务端智能路由。 */
@Data
public class AppAddRequest {

    @NotBlank(message = "初始化需求不能为空")
    @Size(max = 10000, message = "初始化需求不能超过 10000 个字符")
    private String initPrompt;

    /** 接收 auto、html、multi_file、vue_project 等稳定值，避免暴露 Java 枚举名称。 */
    private String codeGenType;

    @Size(max = 32, message = "分类不能超过 32 个字符")
    private String category;

    @Size(max = 500, message = "标签总长度不能超过 500 个字符")
    private String tags;

    private String visibility = AppVisibilityEnum.PRIVATE.getValue();
}
