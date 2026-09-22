package com.lian.aicode.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 读取工程源文件目录结构的工具。 */
@Slf4j
public final class FileDirReadTool extends BaseProjectTool {

    private static final int MAX_ENTRIES = 200;

    public FileDirReadTool(ProjectToolContext context) {
        super(context, "readDir", "读取目录");
    }

    @Tool("读取 Vue 工程目录结构；省略路径表示工程根目录")
    public String readDir(@P("目录的相对路径，可为空") String relativeDirPath,
                          @ToolMemoryId Long ignoredAppId) {
        try {
            Path root = policy().resolveDirectory(relativeDirPath);
            List<String> entries = new ArrayList<>(MAX_ENTRIES);
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(root) && policy().isIgnoredPath(directory)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!directory.equals(root)) {
                        if (entries.size() >= MAX_ENTRIES) {
                            return FileVisitResult.TERMINATE;
                        }
                        entries.add(policy().relative(directory) + "/");
                    }
                    return entries.size() >= MAX_ENTRIES
                            ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                    if (!Files.isSymbolicLink(path)
                            && !policy().isIgnoredPath(path)
                            && entries.size() < MAX_ENTRIES) {
                        entries.add(policy().relative(path));
                    }
                    return entries.size() >= MAX_ENTRIES
                            ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }
            });
            entries.sort(Comparator.naturalOrder());
            StringBuilder result = new StringBuilder("工程目录结构：\n");
            entries.forEach(entry -> result.append(entry).append('\n'));
            log.info("AI 目录读取：actor={}, appId={}, version={}, path={}, result=成功",
                    actor(), context.getAppId(), context.getVersionNo(),
                    relativeDirPath == null || relativeDirPath.isBlank() ? "." : relativeDirPath);
            return result.toString();
        } catch (IOException | RuntimeException exception) {
            log.warn("AI 目录读取失败：actor={}, appId={}, version={}, path={}, reason={}",
                    actor(), context.getAppId(), context.getVersionNo(), relativeDirPath,
                    exception.getClass().getSimpleName());
            return "目录读取失败，请检查相对路径";
        }
    }
}
