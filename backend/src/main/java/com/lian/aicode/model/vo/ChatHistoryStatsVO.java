package com.lian.aicode.model.vo;

import lombok.Builder;
import lombok.Data;

/** 应用对话统计；轮次以用户消息数为准，失败和取消也会计入。 */
@Data
@Builder
public class ChatHistoryStatsVO {

    private Long appId;
    private long messageCount;
    private long roundCount;
    private String lastCreateTime;
    private String summaryUpdatedTime;
}
