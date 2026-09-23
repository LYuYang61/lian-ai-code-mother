package com.lian.aicode.service.impl;

import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import com.lian.aicode.service.AppStorageService;
import com.lian.aicode.service.ProjectDownloadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 项目下载打包实现。
 *
 * <p>下载的是源目录，不是 Vue 的 dist；依赖、构建产物、版本控制目录和常见凭据文件均被排除，
 * 避免把服务器运行环境或敏感配置带给下载者。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectDownloadServiceImpl implements ProjectDownloadService {

    private static final Set<String> IGNORED_PATH_NAMES = Set.of(
            "node_modules", "dist", "build", "target", ".git", ".idea", ".vscode", ".mvn", ".ds_store");

    private static final Set<String> IGNORED_EXTENSIONS = Set.of(".log", ".tmp", ".cache");

    private final AppStorageService storageService;

    @Override
    public DownloadResult writeZip(Path projectDirectory, OutputStream outputStream) throws IOException {
        Path directory = validateDirectory(projectDirectory);
        AtomicInteger fileCount = new AtomicInteger();
        try (ZipOutputStream zip = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(directory) && isIgnoredPath(directory, dir)
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                    if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)
                            || isIgnoredPath(directory, path)
                            || isSensitivePath(directory.relativize(path).normalize())) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path relative = directory.relativize(path).normalize();
                    if (relative.startsWith("..")) {
                        throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "非法下载路径");
                    }
                    zip.putNextEntry(new ZipEntry(relative.toString().replace('\\', '/')));
                    Files.copy(path, zip);
                    zip.closeEntry();
                    fileCount.incrementAndGet();
                    return FileVisitResult.CONTINUE;
                }
            });
            zip.finish();
        }
        log.info("项目源代码打包完成：fileCount={}, result=成功", fileCount.get());
        return new DownloadResult(fileCount.get());
    }

    private Path validateDirectory(Path projectDirectory) {
        if (projectDirectory == null || !Files.isDirectory(projectDirectory)
                || Files.isSymbolicLink(projectDirectory)
                || !storageService.isInsideManagedRoot(projectDirectory)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "代码目录不存在");
        }
        return projectDirectory.toAbsolutePath().normalize();
    }

    private boolean isIgnoredPath(Path root, Path path) {
        for (Path segment : root.relativize(path).normalize()) {
            String name = segment.toString().toLowerCase(Locale.ROOT);
            if (IGNORED_PATH_NAMES.contains(name) || hasIgnoredExtension(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasIgnoredExtension(String name) {
        return IGNORED_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private boolean isSensitivePath(Path relative) {
        for (Path segment : relative) {
            String name = segment.toString().toLowerCase(Locale.ROOT);
            if (name.equals(".env") || name.startsWith(".env.") || name.equals(".npmrc")
                    || name.equals(".yarnrc") || name.equals(".yarnrc.yml")
                    || name.equals("id_rsa") || name.equals("secrets") || name.equals("credentials")
                    || name.endsWith(".pem") || name.endsWith(".key")) {
                return true;
            }
        }
        return false;
    }
}
