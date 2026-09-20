package com.lian.aicode.model.dto.common;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 通用 ID 请求。 */
@Data
public class IdRequest {

    @NotNull(message = "id 不能为空")
    private Long id;
}
