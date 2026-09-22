package com.lian.aicode.model.dto.app;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** 添加或移除协作者请求；userId 与 userAccount 二选一，移除时 role 字段可以为空。 */
@Data
public class AppCollaboratorRequest {

    @NotNull(message = "应用 id 不能为空")
    @Positive(message = "应用 id 必须为正数")
    private Long appId;

    /** 协作者用户 id；与 userAccount 至少提供一个。 */
    @Positive(message = "协作者用户 id 必须为正数")
    private Long userId;

    /** 协作者登录账号；相比长数字 id 更便于填写，服务端解析为用户 id。 */
    private String userAccount;

    /** viewer / editor；添加时默认为 editor。 */
    private String role;
}
