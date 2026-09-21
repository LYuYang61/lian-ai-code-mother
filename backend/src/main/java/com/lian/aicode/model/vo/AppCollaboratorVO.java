package com.lian.aicode.model.vo;

import lombok.Builder;
import lombok.Data;

/** 协作者公开资料和当前应用内角色。 */
@Data
@Builder
public class AppCollaboratorVO {

    private Long id;
    private Long appId;
    private Long userId;
    private String userAccount;
    private String userName;
    private String userAvatar;
    private String role;
    private String createTime;
}
