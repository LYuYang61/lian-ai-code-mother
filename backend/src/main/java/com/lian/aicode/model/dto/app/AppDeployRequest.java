package com.lian.aicode.model.dto.app;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 部署当前可用版本。 */
@Data
public class AppDeployRequest {

    @NotNull(message = "应用 id 不能为空")
    private Long appId;
}
