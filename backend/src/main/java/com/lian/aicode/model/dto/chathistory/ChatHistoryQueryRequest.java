package com.lian.aicode.model.dto.chathistory;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/** 管理员对话历史查询条件；排序字段由服务端白名单映射，不能直接拼接 SQL。 */
@Data
public class ChatHistoryQueryRequest {

    @Min(value = 1, message = "页码必须从 1 开始")
    private long pageNum = 1;

    @Min(value = 1, message = "每页至少 1 条")
    @Max(value = 200, message = "每页最多 200 条")
    private long pageSize = 20;

    @Min(value = 1, message = "消息 id 必须为正数")
    private Long id;

    private String message;
    private String messageType;

    @Min(value = 1, message = "应用 id 必须为正数")
    private Long appId;

    @Min(value = 1, message = "用户 id 必须为正数")
    private Long userId;

    private String sortField = "createTime";
    private String sortOrder = "desc";
}
