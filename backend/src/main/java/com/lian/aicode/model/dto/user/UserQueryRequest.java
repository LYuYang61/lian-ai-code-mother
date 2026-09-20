package com.lian.aicode.model.dto.user;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/** 管理员用户分页查询条件。 */
@Data
public class UserQueryRequest {

    @Min(1)
    private long pageNum = 1;

    @Min(1)
    @Max(50)
    private long pageSize = 20;

    private String userAccount;
    private String userRole;
}
