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

/** 受控写文件工具；模型不能指定工程根目录或绝对路径。 */
@Slf4j
public final class FileWriteTool extends BaseProjectTool {

    public FileWriteTool(ProjectToolContext context) {
        super(context, "writeFile", "写入文件");
    }

    @Tool("写入 Vue 工程文件。只能提供工程根目录内的相对路径，不能写入 node_modules、dist 或密钥文件")
    public String writeFile(@P("文件的相对路径，例如 src/App.vue") String relativeFilePath,
                            @P("完整的 UTF-8 文件内容") String content,
                            @ToolMemoryId Long ignoredAppId) {
        if (context.isCancelled()) {
            log.info("AI 文件写入跳过：actor={}, appId={}, version={}, result=任务已取消",
                    actor(), context.getAppId(), context.getVersionNo());
            return cancellationMessage();
        }
        Path target = null;
        try {
            target = policy().resolveFile(relativeFilePath);
            policy().assertWritable(target, content);
            Path parent = target.getParent();
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, ".aicode-write-", ".tmp");
            try {
                Files.writeString(temporary, content, StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                if (context.isCancelled()) {
                    log.info("AI 文件写入中止：actor={}, appId={}, version={}, result=任务已取消",
                            actor(), context.getAppId(), context.getVersionNo());
                    return cancellationMessage();
                }
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                // 写入内容由模型本轮提供，写完即视为已读，可立即继续 modifyFile 精修。
                context.markFileRead(target);
            } finally {
                Files.deleteIfExists(temporary);
            }
            log.info("AI 文件写入：actor={}, appId={}, version={}, path={}, result=成功",
                    actor(), context.getAppId(), context.getVersionNo(), policy().relative(target));
            return "文件写入成功：" + policy().relative(target);
        } catch (Exception exception) {
            // 取消可能发生在路径校验或文件写入过程中，不能被通用失败消息吞掉，
            // 否则模型可能把取消误判为普通路径错误并继续重试。
            if (context.isCancelled()) {
                log.info("AI 文件写入中止：actor={}, appId={}, version={}, result=任务已取消",
                        actor(), context.getAppId(), context.getVersionNo());
                return cancellationMessage();
            }
            String path = target == null ? safePath(relativeFilePath) : policy().relative(target);
            log.warn("AI 文件写入失败：actor={}, appId={}, version={}, path={}, reason={}",
                    actor(), context.getAppId(), context.getVersionNo(), path,
                    exception.getClass().getSimpleName());
            return "文件写入失败：" + path + "，请检查路径、文件大小或工程限制";
        }
    }

    private String safePath(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        return value.replace('\\', '/').substring(0, Math.min(value.length(), 160));
    }
}
