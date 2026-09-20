package com.lian.aicode.model.vo;

import lombok.Builder;
import lombok.Data;

/** 用户公开信息，不包含密码哈希。 */
@Data
@Builder
public class UserVO {

    private Long id;
    private String userAccount;
    private String userName;
    private String userAvatar;
    private String userProfile;
    private String userRole;
    private String createTime;
}
