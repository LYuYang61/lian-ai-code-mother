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

/** AI 应用主实体，保存权限、生成、版本和部署的当前快照。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("app")
public class App implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("app_name")
    private String appName;

    private String cover;

    @Column("init_prompt")
    private String initPrompt;

    @Column("code_gen_type")
    private String codeGenType;

    @Column("deploy_key")
    private String deployKey;

    @Column("deployed_time")
    private LocalDateTime deployedTime;

    private Integer priority;

    @Column("user_id")
    private Long userId;

    private String visibility;

    private String category;

    /** 以逗号分隔的标签；输入会被标准化，查询使用受控 LIKE，后续可平滑迁移到 ES。 */
    private String tags;

    @Column("generation_status")
    private String generationStatus;

    @Column("current_version")
    private Integer currentVersion;

    @Column("generation_message")
    private String generationMessage;

    @Column("featured_status")
    private String featuredStatus;

    @Column("featured_reason")
    private String featuredReason;

    @Column("deployment_status")
    private String deploymentStatus;

    @Column("edit_time")
    private LocalDateTime editTime;

    @Column("create_time")
    private LocalDateTime createTime;

    @Column("update_time")
    private LocalDateTime updateTime;

    @Column(value = "is_delete", isLogicDelete = true)
    private Integer isDelete;
}
