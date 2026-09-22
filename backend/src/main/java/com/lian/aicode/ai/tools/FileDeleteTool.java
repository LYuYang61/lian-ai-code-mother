package com.lian.aicode.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

/** 删除工程源文件的工具；入口和构建配置文件受保护。 */
@Slf4j
public final class FileDeleteTool extends BaseProjectTool {

    public FileDeleteTool(ProjectToolContext context) {
        super(context, "deleteFile", "删除文件");
    }

    @Tool("删除 Vue 工程中的一个不再需要的源文件；不能删除入口、构建配置、依赖目录或构建产物")
    public String deleteFile(@P("文件的相对路径") String relativeFilePath,
                             @ToolMemoryId Long ignoredAppId) {
        try {
            Path path = policy().resolveFile(relativeFilePath);
            policy().assertDeletable(path);
            if (!Files.exists(path)) {
                return "文件不存在，无需删除：" + relativeFilePath;
            }
            Files.delete(path);
            log.info("AI 文件删除：actor={}, appId={}, version={}, path={}, result=成功",
                    actor(), context.getAppId(), context.getVersionNo(), policy().relative(path));
            return "文件删除成功：" + policy().relative(path);
        } catch (Exception exception) {
            log.warn("AI 文件删除失败：actor={}, appId={}, version={}, path={}, reason={}",
                    actor(), context.getAppId(), context.getVersionNo(), relativeFilePath,
                    exception.getClass().getSimpleName());
            return "文件删除失败，请检查路径或文件保护规则";
        }
    }
}
