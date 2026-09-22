package com.lian.aicode.ai.tools;

import java.util.List;
import java.util.Map;

/** 一次 Vue 生成任务的工具集合，同时提供前端展示所需的中文名称。 */
public final class ProjectToolBundle {

    private final List<Object> tools;
    private final Map<String, String> displayNames;

    public ProjectToolBundle(ProjectToolContext context) {
        FileWriteTool write = new FileWriteTool(context);
        FileReadTool read = new FileReadTool(context);
        FileModifyTool modify = new FileModifyTool(context);
        FileDeleteTool delete = new FileDeleteTool(context);
        FileDirReadTool dir = new FileDirReadTool(context);
        ExitTool exit = new ExitTool(context);
        this.tools = List.of(write, read, modify, delete, dir, exit);
        this.displayNames = Map.of(
                "writeFile", write.getDisplayName(),
                "readFile", read.getDisplayName(),
                "modifyFile", modify.getDisplayName(),
                "deleteFile", delete.getDisplayName(),
                "readDir", dir.getDisplayName(),
                "exit", exit.getDisplayName()
        );
    }

    public List<Object> tools() {
        return tools;
    }

    public String displayName(String toolName) {
        return displayNames.getOrDefault(toolName, "未知工具");
    }
}
