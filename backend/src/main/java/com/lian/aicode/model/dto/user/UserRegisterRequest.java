package com.lian.aicode.model.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 公开注册请求；角色由服务端固定为普通用户。 */
@Data
public class UserRegisterRequest {

    @NotBlank(message = "账号不能为空")
    @Size(min = 4, max = 32, message = "账号长度应为 4-32 个字符")
    @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "账号只能包含字母、数字和下划线")
    private String userAccount;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 72, message = "密码长度应为 8-72 个字符")
    private String userPassword;

    @Size(max = 32, message = "昵称不能超过 32 个字符")
    private String userName;
}
