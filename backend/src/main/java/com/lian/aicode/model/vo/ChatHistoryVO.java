package com.lian.aicode.model.vo;

import lombok.Builder;
import lombok.Data;

/** 对话历史视图。 */
@Data
@Builder
public class ChatHistoryVO {

    private Long id;
    private Long appId;
    private String message;
    private String messageType;
    private Integer versionNo;
    private String createTime;
}
