package com.lian.aicode.ai.tools;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 工程文件工具的公共元数据。 */
@Getter
@RequiredArgsConstructor
public abstract class BaseProjectTool {

    protected static final String CANCELLATION_MESSAGE = "生成任务已被用户取消，请停止调用文件工具";

    protected final ProjectToolContext context;
    private final String toolName;
    private final String displayName;

    protected ProjectPathPolicy policy() {
        return new ProjectPathPolicy(context);
    }

    protected String actor() {
        return context.getActorAccount();
    }

    protected String cancellationMessage() {
        return CANCELLATION_MESSAGE;
    }
}
