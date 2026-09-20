package com.lian.aicode.model.dto.user;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 管理员用户资料和角色维护请求。 */
@Data
public class UserAdminUpdateRequest {

    @NotNull(message = "id 不能为空")
    private Long id;

    @Size(max = 32, message = "昵称不能超过 32 个字符")
    private String userName;

    @Size(max = 512, message = "头像地址不能超过 512 个字符")
    private String userAvatar;

    @Size(max = 512, message = "个人简介不能超过 512 个字符")
    private String userProfile;

    private String userRole;
}
