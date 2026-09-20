package com.lian.aicode.model.dto.app;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 应用分页查询条件。
 *
 * <p>排序字段最终由服务端白名单映射为固定列名，不能把前端字符串直接拼接进 SQL。</p>
 */
@Data
public class AppQueryRequest {

    @Min(value = 1, message = "页码必须从 1 开始")
    private long pageNum = 1;

    @Min(value = 1, message = "每页至少 1 条")
    @Max(value = 1000, message = "每页最多 1000 条")
    private long pageSize = 12;

    /** 管理员精确筛选字段；普通用户接口只会使用允许公开的筛选条件。 */
    @Min(value = 1, message = "应用 id 必须为正数")
    private Long id;

    private String appName;
    private String searchText;
    private String cover;
    private String initPrompt;
    private String codeGenType;
    private String deployKey;

    @Min(value = 0, message = "优先级不能小于 0")
    @Max(value = 9999, message = "优先级不能超过 9999")
    private Integer priority;
    private Long userId;
    private String category;
    private String tags;
    private String tag;
    private String visibility;
    private String generationStatus;
    private Integer currentVersion;
    private String generationMessage;
    private String deploymentStatus;
    private String featuredStatus;
    private String featuredReason;
    private String sortField = "createTime";
    private String sortOrder = "desc";
}
