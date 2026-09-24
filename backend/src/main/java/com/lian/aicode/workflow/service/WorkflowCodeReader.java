package com.lian.aicode.workflow.service;

import com.lian.aicode.config.AiWorkflowProperties;
import com.lian.aicode.service.AppStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 安全、限量地读取生成工程，作为代码质量模型的输入。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowCodeReader {

    private static final Set<String> EXTENSIONS = Set.of(
            ".html", ".htm", ".css", ".js", ".json", ".vue", ".ts", ".jsx", ".tsx"
    );
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            "node_modules", "dist", "target", ".git"
    );
    private static final Set<String> IGNORED_FILES = Set.of(
            "package-lock.json", "pnpm-lock.yaml", "yarn.lock"
    );

    private final AppStorageService storageService;
    private final AiWorkflowProperties properties;

    public CodeSnapshot read(Path root) {
        return readSnapshot(root, null);
    }

    /**
     * 修改轮的变更范围读取：与基线目录逐文件对比，只把新增/内容变化的文件交给质检，
     * 删除的文件以清单列出。基线无效（创建轮或目录缺失）时回退全量读取。
     * 目的：质检评估"本次变更"而不是整个工程，避免为存量问题反复重试
     * （2026-09-24 实测：41 字符需求因存量问题重试三轮共 227 秒后整轮作废）。
     */
    public CodeSnapshot readChanged(Path baseline, Path current) {
        Path normalizedBaseline = baseline == null ? null : baseline.toAbsolutePath().normalize();
        if (normalizedBaseline == null
                || !normalizedBaseline.startsWith(storageService.getCodeOutputRoot())
                || !Files.isDirectory(normalizedBaseline)
                || Files.isSymbolicLink(normalizedBaseline)) {
            log.info("质检基线不可用，回退全量读取：baseline={}", normalizedBaseline);
            return readSnapshot(current, null);
        }
        return readSnapshot(current, collectCodeFiles(normalizedBaseline));
    }

    private CodeSnapshot readSnapshot(Path root, Map<String, String> baselineFiles) {
        if (root == null) {
            return new CodeSnapshot("", 0);
        }
        Path normalized = root.toAbsolutePath().normalize();
        if (!normalized.startsWith(storageService.getCodeOutputRoot())
                || !Files.isDirectory(normalized)
                || Files.isSymbolicLink(normalized)
                || !storageService.isInsideManagedRoot(normalized)) {
            log.warn("读取代码质检输入拒绝：reason=目录不在受控代码根目录");
            return new CodeSnapshot("", 0);
        }
        boolean changesOnly = baselineFiles != null;
        // 匿名访问器内会移除访问到的键，剩余即"本轮被删除"的文件清单。
        Map<String, String> remainingBaseline = changesOnly
                ? new LinkedHashMap<>(baselineFiles) : null;
        StringBuilder content = new StringBuilder(changesOnly
                ? "# 本次变更的文件（相对上一可用版本）和当前内容\n\n"
                : "# 项目文件结构和代码内容\n\n");
        int[] fileCount = {0};
        int[] totalChars = {content.length()};
        try {
            Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(normalized)
                            && (Files.isSymbolicLink(directory)
                            || directory.getFileName().toString().startsWith(".")
                            || isIgnoredDirectory(directory.getFileName().toString()))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (fileCount[0] >= properties.safeQualityMaxFiles()
                            || Files.isSymbolicLink(file)
                            || IGNORED_FILES.contains(file.getFileName().toString().toLowerCase(Locale.ROOT))
                            || !isCodeFile(file)
                            || Files.size(file) > 2 * 1024 * 1024) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = normalized.relativize(file).toString().replace('\\', '/');
                    String fileContent = Files.readString(file, StandardCharsets.UTF_8);
                    if (remainingBaseline != null) {
                        String baselineContent = remainingBaseline.remove(relative);
                        if (fileContent.equals(baselineContent)) {
                            return FileVisitResult.CONTINUE;
                        }
                    }
                    int remaining = properties.safeQualityMaxCodeChars() - totalChars[0];
                    if (remaining <= 0) {
                        return FileVisitResult.TERMINATE;
                    }
                    int available = Math.min(fileContent.length(), Math.max(remaining - relative.length() - 30, 0));
                    content.append("## 文件: ").append(relative).append("\n\n")
                            .append(fileContent, 0, available).append("\n\n");
                    totalChars[0] = content.length();
                    fileCount[0]++;
                    return totalChars[0] >= properties.safeQualityMaxCodeChars()
                            ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            log.warn("读取代码质检输入失败：reason={}", exception.getClass().getSimpleName());
            return new CodeSnapshot("", fileCount[0]);
        }
        if (remainingBaseline != null && !remainingBaseline.isEmpty()) {
            content.append("# 本轮删除的文件（仅清单，无内容）\n");
            remainingBaseline.keySet().forEach(name -> content.append("- ").append(name).append('\n'));
            content.append('\n');
        }
        log.info("读取代码质检输入完成：changesOnly={}, fileCount={}, chars={}",
                changesOnly, fileCount[0], content.length());
        return new CodeSnapshot(content.toString(), fileCount[0]);
    }

    /** 收集目录内全部受控代码文件内容，键为相对路径。 */
    private Map<String, String> collectCodeFiles(Path root) {
        Map<String, String> files = new LinkedHashMap<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(root)
                            && (Files.isSymbolicLink(directory)
                            || directory.getFileName().toString().startsWith(".")
                            || isIgnoredDirectory(directory.getFileName().toString()))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (!Files.isSymbolicLink(file)
                            && !IGNORED_FILES.contains(file.getFileName().toString().toLowerCase(Locale.ROOT))
                            && isCodeFile(file)
                            && Files.size(file) <= 2 * 1024 * 1024) {
                        files.put(root.relativize(file).toString().replace('\\', '/'),
                                Files.readString(file, StandardCharsets.UTF_8));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            log.warn("读取质检基线文件失败：reason={}", exception.getClass().getSimpleName());
        }
        return files;
    }

    private boolean isIgnoredDirectory(String name) {
        return IGNORED_DIRECTORIES.contains(name.toLowerCase(Locale.ROOT));
    }

    private boolean isCodeFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    public record CodeSnapshot(String content, int fileCount) {
    }
}
