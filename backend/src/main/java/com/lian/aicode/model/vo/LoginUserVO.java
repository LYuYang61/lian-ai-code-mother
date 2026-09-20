package com.lian.aicode.model.vo;

import lombok.Builder;
import lombok.Data;

/** 登录成功后返回的会话用户信息。 */
@Data
@Builder
public class LoginUserVO {

    private Long id;
    private String userAccount;
    private String userName;
    private String userAvatar;
    private String userProfile;
    private String userRole;
}
