package com.lian.aicode.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 受控读取文件工具；拒绝读取依赖、构建产物和凭据文件。 */
@Slf4j
public final class FileReadTool extends BaseProjectTool {

    private static final int MAX_READ_BYTES = 512 * 1024;

    public FileReadTool(ProjectToolContext context) {
        super(context, "readFile", "读取文件");
    }

    @Tool("读取 Vue 工程中的一个源文件，只能使用工程根目录内的相对路径")
    public String readFile(@P("文件的相对路径") String relativeFilePath,
                           @ToolMemoryId Long ignoredAppId) {
        try {
            Path path = policy().resolveFile(relativeFilePath);
            if (!Files.isRegularFile(path)) {
                return "文件不存在：" + relativeFilePath;
            }
            if (Files.size(path) > MAX_READ_BYTES) {
                log.warn("AI 文件读取拒绝：actor={}, appId={}, version={}, path={}, reason=文件过大",
                        actor(), context.getAppId(), context.getVersionNo(), policy().relative(path));
                return "文件过大，无法读取：" + policy().relative(path);
            }
            log.info("AI 文件读取：actor={}, appId={}, version={}, path={}, result=成功",
                    actor(), context.getAppId(), context.getVersionNo(), policy().relative(path));
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            log.warn("AI 文件读取失败：actor={}, appId={}, version={}, path={}, reason={}",
                    actor(), context.getAppId(), context.getVersionNo(), relativeFilePath,
                    exception.getClass().getSimpleName());
            return "文件读取失败，请检查相对路径";
        }
    }
}
