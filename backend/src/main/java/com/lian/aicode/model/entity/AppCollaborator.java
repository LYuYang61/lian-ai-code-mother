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

/** 应用协作者；协作者只共享应用工作区权限，不自动获得应用运营权限。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("app_collaborator")
public class AppCollaborator implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("app_id")
    private Long appId;

    @Column("user_id")
    private Long userId;

    private String role;

    @Column("invited_by")
    private Long invitedBy;

    @Column("create_time")
    private LocalDateTime createTime;

    @Column("update_time")
    private LocalDateTime updateTime;

    @Column(value = "is_delete", isLogicDelete = true)
    private Integer isDelete;
}
