package com.lian.aicode.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** 受控文本替换工具，适合在模板基础上进行小范围修改。 */
@Slf4j
public final class FileModifyTool extends BaseProjectTool {

    public FileModifyTool(ProjectToolContext context) {
        super(context, "modifyFile", "修改文件");
    }

    @Tool("在 Vue 工程源文件中用新内容替换一段旧内容；替换前必须先读取文件")
    public String modifyFile(@P("文件的相对路径") String relativeFilePath,
                             @P("需要被完整匹配的旧文本") String oldContent,
                             @P("替换后的新文本") String newContent,
                             @ToolMemoryId Long ignoredAppId) {
        if (context.isCancelled()) {
            log.info("AI 文件修改跳过：actor={}, appId={}, version={}, result=任务已取消",
                    actor(), context.getAppId(), context.getVersionNo());
            return "生成任务已被用户取消，请停止调用文件工具";
        }
        try {
            Path path = policy().resolveFile(relativeFilePath);
            if (!Files.isRegularFile(path) || oldContent == null || newContent == null) {
                return "修改失败：文件不存在或参数为空";
            }
            String original = Files.readString(path, StandardCharsets.UTF_8);
            if (!original.contains(oldContent)) {
                return "修改失败：未找到需要替换的文本";
            }
            String modified = original.replace(oldContent, newContent);
            policy().assertWritable(path, modified);
            Path temporary = Files.createTempFile(path.getParent(), ".aicode-modify-", ".tmp");
            try {
                Files.writeString(temporary, modified, StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                try {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            log.info("AI 文件修改：actor={}, appId={}, version={}, path={}, result=成功",
                    actor(), context.getAppId(), context.getVersionNo(), policy().relative(path));
            return "文件修改成功：" + policy().relative(path);
        } catch (Exception exception) {
            // 取消可能发生在读取、替换或写入过程中，优先返回可驱动模型停止的明确结果。
            if (context.isCancelled()) {
                log.info("AI 文件修改中止：actor={}, appId={}, version={}, result=任务已取消",
                        actor(), context.getAppId(), context.getVersionNo());
                return "生成任务已被用户取消，请停止调用文件工具";
            }
            log.warn("AI 文件修改失败：actor={}, appId={}, version={}, path={}, reason={}",
                    actor(), context.getAppId(), context.getVersionNo(), relativeFilePath,
                    exception.getClass().getSimpleName());
            return "文件修改失败，请检查路径、替换文本或工程限制";
        }
    }
}
