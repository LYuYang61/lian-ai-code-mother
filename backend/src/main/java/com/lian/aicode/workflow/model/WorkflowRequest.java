package com.lian.aicode.workflow.model;

import com.lian.aicode.model.enums.CodeGenTypeEnum;
import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;

/** 应用服务传给工作流的业务上下文，不暴露给 HTTP 客户端。 */
@Value
@Builder
public class WorkflowRequest {

    Long appId;
    Integer versionNo;
    Long excludedMessageId;
    String actorAccount;
    String prompt;
    Path outputDirectory;
    /** 修改轮的基线版本目录（上一可用版本），供质检做变更范围评估；创建轮为空。 */
    Path baselineDirectory;
    CodeGenTypeEnum generationType;
    boolean modification;
}
