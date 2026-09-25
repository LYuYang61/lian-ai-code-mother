package com.lian.aicode.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户公开信息，不包含密码哈希。
 *
 * <p>NoArgs/AllArgs 构造器供 Jackson 反序列化使用（作为 AppVO.owner 进入缓存值）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserVO {

    private Long id;
    private String userAccount;
    private String userName;
    private String userAvatar;
    private String userProfile;
    private String userRole;
    private String createTime;
}
