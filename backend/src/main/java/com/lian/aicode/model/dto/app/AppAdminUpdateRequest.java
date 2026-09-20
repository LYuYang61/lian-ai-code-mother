package com.lian.aicode.model.dto.app;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 管理员审核和运营字段更新请求。 */
@Data
public class AppAdminUpdateRequest {

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

    @Min(value = 0, message = "优先级不能小于 0")
    @Max(value = 9999, message = "优先级不能超过 9999")
    private Integer priority;

    private String visibility;

    private String featuredStatus;

    @Size(max = 500, message = "精选说明不能超过 500 个字符")
    private String featuredReason;
}
