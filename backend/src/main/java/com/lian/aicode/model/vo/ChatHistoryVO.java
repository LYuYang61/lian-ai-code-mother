package com.lian.aicode.model.vo;

import lombok.Builder;
import lombok.Data;

/** 对话历史视图。 */
@Data
@Builder
public class ChatHistoryVO {

    private Long id;
    private Long appId;
    private Long userId;
    private String message;
    private String messageType;
    private Long parentId;
    private Integer versionNo;
    private String fileList;
    private String createTime;
}
