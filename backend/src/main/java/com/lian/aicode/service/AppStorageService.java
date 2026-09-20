package com.lian.aicode.service;

import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 应用代码文件的唯一入口。
 *
 * <p>数据库只保存相对路径，所有外部输入经过 ID、版本号和 deployKey 校验后才能参与拼接；
 * 复制部署先写临时目录，再切换到部署目录，避免用户看到半套文件。</p>
 */
@Service
@Slf4j
public class AppStorageService {

    // 兼容教程阶段已经生成的 6 位 deployKey；新部署仍使用 12 位随机标识。
    private static final Pattern DEPLOY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]{6,64}");

    private final Path codeOutputRoot;
    private final Path deployRoot;

    public AppStorageService(@Value("${app.code-output-root}") String codeOutputRoot,
                             @Value("${app.deploy-output-root}") String deployRoot) {
        this.codeOutputRoot = Path.of(codeOutputRoot).toAbsolutePath().normalize();
        this.deployRoot = Path.of(deployRoot).toAbsolutePath().normalize();
    }

    public Path getCodeOutputRoot() {
        return codeOutputRoot;
    }

    public Path versionDirectory(Long appId, int versionNo) {
        if (appId == null || appId <= 0 || versionNo <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用版本参数错误");
        }
        return codeOutputRoot.resolve("app").resolve(appId.toString()).resolve("v" + versionNo)
                .normalize();
    }

    public String versionRelativePath(Long appId, int versionNo) {
        return Path.of("app", appId.toString(), "v" + versionNo).toString();
    }

    public Path resolveVersionPath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "代码版本目录不存在");
        }
        Path path = codeOutputRoot.resolve(relativePath).normalize();
        if (!path.startsWith(codeOutputRoot)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "非法代码目录");
        }
        if (Files.exists(path) && !isInsideRealRoot(codeOutputRoot, path)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "代码目录不在受控根目录内");
        }
        return path;
    }

    public Path deployDirectory(String deployKey) {
        if (deployKey == null || !DEPLOY_KEY_PATTERN.matcher(deployKey).matches()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "部署标识无效");
        }
        return deployRoot.resolve(deployKey).normalize();
    }

    public void deploy(Path sourceDirectory, String deployKey) {
        Path normalizedSource = sourceDirectory == null ? null : sourceDirectory.toAbsolutePath().normalize();
        if (normalizedSource == null || !normalizedSource.startsWith(codeOutputRoot)
                || !Files.isDirectory(normalizedSource) || !isInsideRealRoot(codeOutputRoot, normalizedSource)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "可部署代码目录不存在");
        }
        Path targetDirectory = deployDirectory(deployKey);
        Path temporaryDirectory = deployRoot.resolve(".deploy-" + deployKey + "-" + UUID.randomUUID())
                .normalize();
        Path backupDirectory = deployRoot.resolve(".backup-" + deployKey + "-" + UUID.randomUUID())
                .normalize();
        try {
            Files.createDirectories(deployRoot);
            copyDirectory(normalizedSource, temporaryDirectory);
            // 先把旧目录移到同一文件系统下的备份目录，再切换新目录，避免先删除旧目录造成短暂 404。
            if (Files.exists(targetDirectory)) {
                moveDirectory(targetDirectory, backupDirectory);
            }
            moveDirectory(temporaryDirectory, targetDirectory);
            deleteRecursively(backupDirectory);
        } catch (IOException exception) {
            deleteRecursively(temporaryDirectory);
            // 新目录切换失败时尽量恢复旧版本；恢复失败只记录日志，不覆盖主异常。
            if (!Files.exists(targetDirectory) && Files.exists(backupDirectory)) {
                try {
                    moveDirectory(backupDirectory, targetDirectory);
                } catch (IOException restoreException) {
                    log.error("恢复旧部署目录失败：{}", targetDirectory, restoreException);
                }
            }
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "应用部署文件复制失败", exception);
        }
    }

    private void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            // Windows 普通磁盘、网络盘可能不支持原子目录移动；此时仍然只移动已完整写入的目录。
            Files.move(source, target);
        }
    }

    public void deleteVersionDirectory(Long appId, int versionNo) {
        deleteRecursively(versionDirectory(appId, versionNo));
    }

    public void deleteApplicationFiles(Long appId) {
        if (appId == null || appId <= 0) {
            return;
        }
        deleteRecursively(codeOutputRoot.resolve("app").resolve(appId.toString()).normalize());
    }

    public void deleteDeployment(String deployKey) {
        if (deployKey != null && DEPLOY_KEY_PATTERN.matcher(deployKey).matches()) {
            deleteRecursively(deployDirectory(deployKey));
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        if (Files.isSymbolicLink(source)) {
            throw new IOException("不允许部署符号链接目录");
        }
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("不允许部署符号链接文件");
                }
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative).normalize();
                if (!destination.startsWith(target)) {
                    throw new IOException("非法部署路径");
                }
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(path)) {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private boolean isInsideRealRoot(Path root, Path path) {
        try {
            return path.toRealPath().startsWith(root.toRealPath());
        } catch (IOException exception) {
            return false;
        }
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException exception) {
                    throw new FileCleanupException(exception);
                }
            });
        } catch (IOException | FileCleanupException exception) {
            // 清理属于补偿动作，不能掩盖生成/删除主流程的原始结果，但必须留下可追踪日志。
            log.warn("清理应用文件失败：{}", path, exception);
        }
    }

    private static class FileCleanupException extends RuntimeException {
        private FileCleanupException(IOException cause) {
            super(cause);
        }
    }
}
