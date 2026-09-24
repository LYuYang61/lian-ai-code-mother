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
            if (!Files.isRegularFile(path) || oldContent == null || oldContent.isEmpty() || newContent == null) {
                return "修改失败：文件不存在或参数为空";
            }
            if (!context.hasReadFile(path)) {
                log.info("AI 文件修改拒绝：actor={}, appId={}, version={}, path={}, reason=未先读取",
                        actor(), context.getAppId(), context.getVersionNo(), policy().relative(path));
                return "修改失败：请先使用 readFile 读取目标文件，再调用 modifyFile";
            }
            String original = Files.readString(path, StandardCharsets.UTF_8);
            int firstIndex = original.indexOf(oldContent);
            if (firstIndex < 0) {
                return "修改失败：未找到需要替换的文本";
            }
            if (firstIndex != original.lastIndexOf(oldContent)) {
                return "修改失败：旧文本匹配到多个位置，请提供更精确的上下文";
            }
            String modified = original.substring(0, firstIndex) + newContent
                    + original.substring(firstIndex + oldContent.length());
            policy().assertWritable(path, modified);
            Path temporary = Files.createTempFile(path.getParent(), ".aicode-modify-", ".tmp");
            try {
                Files.writeString(temporary, modified, StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                if (context.isCancelled()) {
                    log.info("AI 文件修改中止：actor={}, appId={}, version={}, result=任务已取消",
                            actor(), context.getAppId(), context.getVersionNo());
                    return cancellationMessage();
                }
                try {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
                // 写入者对自己刚替换出的内容持有最新认知，保留已读标记可继续修改同一文件。
                // 安全兜底是唯一匹配检查：它每次都按磁盘实时内容定位，模型若凭过期记忆构造
                // oldContent 只会"未找到文本"被安全拒绝，不可能错改（2026-09-24 删除整页面任务
                // 实测：改后失效重读让 store 连续 5 处修改变成 10 轮，叠加 10 次未读拒绝耗尽 40 轮上限）。
                context.markFileRead(path);
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
                return cancellationMessage();
            }
            log.warn("AI 文件修改失败：actor={}, appId={}, version={}, path={}, reason={}",
                    actor(), context.getAppId(), context.getVersionNo(), relativeFilePath,
                    exception.getClass().getSimpleName());
            return "文件修改失败，请检查路径、替换文本或工程限制";
        }
    }
}
