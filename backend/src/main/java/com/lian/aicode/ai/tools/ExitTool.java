package com.lian.aicode.ai.tools;

import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

/** 告知模型文件操作已经完成，避免无意义地继续调用工具。 */
@Slf4j
public final class ExitTool extends BaseProjectTool {

    public ExitTool(ProjectToolContext context) {
        super(context, "exit", "结束工具调用");
    }

    @Tool("当工程文件已经生成或修改完成时调用，停止继续调用文件工具")
    public String exit() {
        log.info("AI 工具调用结束：actor={}, appId={}, version={}, result=模型请求结束",
                actor(), context.getAppId(), context.getVersionNo());
        return "文件操作已完成，请输出简短的完成说明";
    }
}
