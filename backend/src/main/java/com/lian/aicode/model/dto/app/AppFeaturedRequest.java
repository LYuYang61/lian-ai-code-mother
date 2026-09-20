package com.lian.aicode.model.dto.app;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 普通用户申请精选，最终 priority 由管理员审核设置。 */
@Data
public class AppFeaturedRequest {

    @NotNull(message = "应用 id 不能为空")
    private Long appId;

    @Size(max = 500, message = "申请说明不能超过 500 个字符")
    private String reason;
}
