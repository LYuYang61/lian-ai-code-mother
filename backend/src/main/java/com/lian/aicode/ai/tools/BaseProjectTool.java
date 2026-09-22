package com.lian.aicode.ai.tools;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 工程文件工具的公共元数据。 */
@Getter
@RequiredArgsConstructor
public abstract class BaseProjectTool {

    protected final ProjectToolContext context;
    private final String toolName;
    private final String displayName;

    protected ProjectPathPolicy policy() {
        return new ProjectPathPolicy(context);
    }

    protected String actor() {
        return context.getActorAccount();
    }
}
