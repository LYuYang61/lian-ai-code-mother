package com.lian.aicode.ai.tools;

import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * AI 文件工具的路径和容量边界。
 *
 * <p>模型输出是不可信输入，不能直接把它拼接到服务器目录。这里同时检查绝对路径、父目录
 * 穿越、符号链接、敏感文件名和工程容量；构建产物与依赖目录也不允许由模型直接修改。</p>
 */
@RequiredArgsConstructor
public final class ProjectPathPolicy {

    private static final Set<String> FORBIDDEN_SEGMENTS = Set.of(
            ".git", "node_modules", "dist", "build", "target", ".idea", ".vscode", ".mvn",
            ".gradle", "coverage", ".cache", "secrets", "credentials"
    );
    private static final Set<String> FORBIDDEN_FILES = Set.of(
            ".env", ".npmrc", ".yarnrc", ".yarnrc.yml", "npmrc", "pnpmfile.cjs", "id_rsa"
    );
    private static final Set<String> PROTECTED_WRITE_FILES = Set.of(
            "package.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock",
            "vite.config.js", "vite.config.ts"
    );
    private static final Set<String> PROTECTED_FILES = Set.of(
            "package.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock",
            "vite.config.js", "vite.config.ts",
            "index.html", "src/main.js", "src/main.ts", "src/app.vue"
    );

    private final ProjectToolContext context;

    public Path resolveFile(String relativePath) {
        String normalizedInput = normalizeRelativePath(relativePath);
        Path path = resolve(normalizedInput);
        if (Files.exists(path) && !Files.isRegularFile(path)) {
            throw forbidden("目标路径不是普通文件");
        }
        return path;
    }

    public Path resolveDirectory(String relativePath) {
        String normalizedInput = relativePath == null ? "" : relativePath.trim();
        if (normalizedInput.isBlank()) {
            return checkedRoot();
        }
        normalizedInput = normalizeRelativePath(normalizedInput);
        Path path = resolve(normalizedInput);
        if (Files.exists(path) && !Files.isDirectory(path)) {
            throw forbidden("目标路径不是目录");
        }
        return path;
    }

    public String relative(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(checkedRoot())) {
            throw forbidden("路径超出工程目录");
        }
        return checkedRoot().relativize(normalized).toString().replace('\\', '/');
    }

    public void assertWritable(Path path, String content) {
        if (context.isCancelled()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成任务已取消");
        }
        if (content == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件内容不能为空");
        }
        String relativePath = relative(path).toLowerCase(Locale.ROOT);
        if (PROTECTED_WRITE_FILES.contains(relativePath)) {
            throw forbidden("模板依赖和构建配置由服务端保护");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > context.getMaxFileSizeBytes()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "单个文件超过大小限制");
        }
        Path parent = path.getParent();
        if (parent == null || !parent.toAbsolutePath().normalize().startsWith(checkedRoot())) {
            throw forbidden("非法文件父目录");
        }
        try {
            if (hasSymbolicLinkBetweenRootAnd(parent)) {
                throw forbidden("文件父目录包含符号链接");
            }
            Files.createDirectories(parent);
            if (hasSymbolicLinkBetweenRootAnd(parent) || !isInsideRealRoot(parent)) {
                throw forbidden("文件父目录包含非法符号链接");
            }
            long currentBytes = 0;
            long fileCount = 0;
            if (Files.isDirectory(checkedRoot())) {
                try (var paths = Files.walk(checkedRoot())) {
                    var regularFiles = paths.filter(Files::isRegularFile)
                            .filter(item -> !Files.isSymbolicLink(item))
                            .filter(item -> !isIgnoredPath(item));
                    var snapshot = regularFiles.toList();
                    fileCount = snapshot.size();
                    for (Path item : snapshot) {
                        currentBytes += Files.size(item);
                    }
                }
            }
            long oldBytes = Files.isRegularFile(path) ? Files.size(path) : 0;
            long newCount = Files.isRegularFile(path) ? fileCount : fileCount + 1;
            if (newCount > context.getMaxFiles()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "工程文件数量超过限制");
            }
            if (currentBytes - oldBytes + bytes.length > context.getMaxTotalBytes()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "工程文件总大小超过限制");
            }
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "检查工程文件容量失败", exception);
        }
    }

    public void assertDeletable(Path path) {
        String value = relative(path).toLowerCase(Locale.ROOT);
        if (PROTECTED_FILES.contains(value)) {
            throw forbidden("工程入口文件不允许由工具删除");
        }
    }

    public boolean isIgnoredPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(checkedRoot())) {
            return true;
        }
        for (Path segment : checkedRoot().relativize(normalized)) {
            String name = segment.toString().toLowerCase(Locale.ROOT);
            if (FORBIDDEN_SEGMENTS.contains(name) || FORBIDDEN_FILES.contains(name) || name.startsWith(".env.")
                    || name.endsWith(".pem") || name.endsWith(".key") || name.equals("id_rsa")) {
                return true;
            }
        }
        return false;
    }

    private Path resolve(String normalizedInput) {
        Path root = checkedRoot();
        final Path relative;
        try {
            relative = Path.of(normalizedInput);
        } catch (InvalidPathException exception) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件路径格式无效", exception);
        }
        if (relative.isAbsolute() || normalizedInput.contains(":")
                || relative.startsWith("..") || isIgnoredPath(root.resolve(relative))) {
            throw forbidden("文件路径不在允许范围内");
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || hasSymbolicLinkBetweenRootAnd(resolved)
                || (Files.exists(resolved) && !isInsideRealRoot(resolved))) {
            throw forbidden("文件路径越过工程目录或包含符号链接");
        }
        return resolved;
    }

    private String normalizeRelativePath(String value) {
        // 2026-09-23 实测模型可能传入 null 路径；先判空给出参数错误，而不是让 trim() 抛 NPE。
        if (value == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件路径不能为空且必须是相对路径");
        }
        String normalized = value.trim().replace('\\', '/');
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.contains("\u0000")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件路径不能为空且必须是相对路径");
        }
        return normalized;
    }

    private Path checkedRoot() {
        Path root = context.getProjectRoot();
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) {
                throw forbidden("工程根目录无效");
            }
            return root;
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "工程根目录不可用", exception);
        }
    }

    private boolean isInsideRealRoot(Path path) {
        try {
            return path.toRealPath().startsWith(checkedRoot().toRealPath());
        } catch (IOException exception) {
            return false;
        }
    }

    /** 检查从工程根到目标路径的每一级，避免先创建目录后才发现父级是符号链接。 */
    private boolean hasSymbolicLinkBetweenRootAnd(Path path) {
        Path root = checkedRoot();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            return true;
        }
        Path current = root;
        for (Path segment : root.relativize(normalized)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return Files.isSymbolicLink(root);
    }

    private BusinessException forbidden(String message) {
        return new BusinessException(ErrorCode.FORBIDDEN_ERROR, message);
    }
}
