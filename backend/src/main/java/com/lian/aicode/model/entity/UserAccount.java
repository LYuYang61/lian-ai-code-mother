package com.lian.aicode.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 登录用户实体。
 *
 * <p>独立使用 {@code user_account} 表，避免直接使用 MySQL 的系统 user 表；数据库密码只保存
 * BCrypt 哈希，绝不保存明文或教程中的固定盐值。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user_account")
public class UserAccount implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("user_account")
    private String userAccount;

    @Column("user_password")
    private String userPassword;

    @Column("user_name")
    private String userName;

    @Column("user_avatar")
    private String userAvatar;

    @Column("user_profile")
    private String userProfile;

    @Column("user_role")
    private String userRole;

    @Column("edit_time")
    private LocalDateTime editTime;

    @Column("create_time")
    private LocalDateTime createTime;

    @Column("update_time")
    private LocalDateTime updateTime;

    @Column(value = "is_delete", isLogicDelete = true)
    private Integer isDelete;
}
