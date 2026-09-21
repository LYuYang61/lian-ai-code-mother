package com.lian.aicode.model.vo;

import lombok.Builder;
import lombok.Data;

/** 对话摘要结果；摘要用于模型上下文，不替代完整历史记录。 */
@Data
@Builder
public class ChatSummaryVO {

    private Long appId;
    private String summary;
    private Long coveredUntilId;
    private String coveredUntilTime;
    private Integer messageCount;
    private String updateTime;
}
