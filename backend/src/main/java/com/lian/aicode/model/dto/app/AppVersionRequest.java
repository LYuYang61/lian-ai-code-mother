package com.lian.aicode.model.dto.app;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 预览、部署或回滚指定版本。 */
@Data
public class AppVersionRequest {

    @NotNull(message = "应用 id 不能为空")
    private Long appId;

    @NotNull(message = "版本号不能为空")
    private Integer versionNo;
}
