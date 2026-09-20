package com.lian.aicode.model.dto.app;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 用户修改应用资料请求，不允许修改所有者、生成状态和优先级。 */
@Data
public class AppUpdateRequest {

    @NotNull(message = "id 不能为空")
    private Long id;

    @Size(max = 64, message = "应用名称不能超过 64 个字符")
    private String appName;

    @Size(max = 512, message = "封面地址不能超过 512 个字符")
    private String cover;

    @Size(max = 32, message = "分类不能超过 32 个字符")
    private String category;

    @Size(max = 500, message = "标签总长度不能超过 500 个字符")
    private String tags;

    private String visibility;
}
