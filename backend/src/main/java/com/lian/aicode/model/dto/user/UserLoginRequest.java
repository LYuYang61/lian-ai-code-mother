package com.lian.aicode.model.dto.user;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 登录请求。 */
@Data
public class UserLoginRequest {

    @NotBlank(message = "账号不能为空")
    private String userAccount;

    @NotBlank(message = "密码不能为空")
    private String userPassword;
}
