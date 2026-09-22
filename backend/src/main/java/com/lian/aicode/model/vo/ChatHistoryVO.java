package com.lian.aicode.model.vo;

import lombok.Builder;
import lombok.Data;

/** 对话历史视图。 */
@Data
@Builder
public class ChatHistoryVO {

    private Long id;
    private Long appId;
    /** 消息所属应用的名称，管理后台直接展示，避免只能看到长 id。 */
    private String appName;
    private Long userId;
    /** 发送者账号与昵称，用于前端区分创建者和协作者；AI 消息为空。 */
    private String userAccount;
    private String userName;
    private String message;
    private String messageType;
    private Long parentId;
    private Integer versionNo;
    private String fileList;
    private String createTime;
}
