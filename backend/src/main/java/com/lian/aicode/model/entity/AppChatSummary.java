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

/** 应用历史对话的压缩摘要；摘要不是用户可见原始历史的替代品。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("app_chat_summary")
public class AppChatSummary implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("app_id")
    private Long appId;

    private String summary;

    @Column("covered_until_id")
    private Long coveredUntilId;

    @Column("covered_until_time")
    private LocalDateTime coveredUntilTime;

    @Column("message_count")
    private Integer messageCount;

    @Column("create_time")
    private LocalDateTime createTime;

    @Column("update_time")
    private LocalDateTime updateTime;

    @Column(value = "is_delete", isLogicDelete = true)
    private Integer isDelete;
}
