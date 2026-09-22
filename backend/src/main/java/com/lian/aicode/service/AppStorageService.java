package com.lian.aicode.service;

import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
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

    /**
     * 校验静态资源控制器使用的根目录仍属于应用管理的代码或部署根目录。
     * 除了字符串规范化，还要检查最终真实路径，避免根目录本身被替换成符号链接。
     */
    public boolean isInsideManagedRoot(Path path) {
        if (path == null || Files.isSymbolicLink(path)) {
            return false;
        }
        try {
            Path realPath = path.toRealPath();
            return realPath.startsWith(codeOutputRoot.toRealPath())
                    || realPath.startsWith(deployRoot.toRealPath());
        } catch (IOException exception) {
            return false;
        }
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

    /** Vue 工程的生产静态目录；HTML 模式直接使用 versionDirectory。 */
    public Path projectDistDirectory(Long appId, int versionNo) {
        return versionDirectory(appId, versionNo).resolve("dist").normalize();
    }

    /**
     * 为新 Vue 版本继承上一可用版本的源文件。
     *
     * <p>构建产物、依赖目录、版本控制目录和敏感文件不会复制；源目录和目标目录都必须
     * 位于受控代码根下，且遍历过程中拒绝符号链接。</p>
     */
    public void copyProjectSource(Path sourceDirectory, Path targetDirectory) {
        Path source = sourceDirectory == null ? null : sourceDirectory.toAbsolutePath().normalize();
        Path target = targetDirectory == null ? null : targetDirectory.toAbsolutePath().normalize();
        if (source == null || target == null || source.equals(target)
                || !source.startsWith(codeOutputRoot) || !target.startsWith(codeOutputRoot)
                || Files.isSymbolicLink(source) || Files.isSymbolicLink(target)
                || hasSymbolicLinkBetween(codeOutputRoot, source)
                || hasSymbolicLinkBetween(codeOutputRoot, target)
                || !Files.isDirectory(source) || !isInsideRealRoot(codeOutputRoot, source)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "版本源目录不在受控代码根目录内");
        }
        try {
            Files.createDirectories(target);
            if (Files.isSymbolicLink(target) || !isInsideRealRoot(codeOutputRoot, target)) {
                throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "版本目标目录不在受控代码根目录内");
            }
            final int[] copiedFiles = {0};
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws IOException {
                    if (Files.isSymbolicLink(directory)) {
                        throw new IOException("不允许复制符号链接目录");
                    }
                    if (!directory.equals(source) && isIgnoredProjectPath(source, directory)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Path destination = target.resolve(source.relativize(directory)).normalize();
                    if (!destination.startsWith(target) || hasSymbolicLinkBetween(target, destination)) {
                        throw new IOException("版本复制路径非法");
                    }
                    Files.createDirectories(destination);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (Files.isSymbolicLink(file)) {
                        throw new IOException("不允许复制符号链接文件");
                    }
                    if (isIgnoredProjectPath(source, file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path destination = target.resolve(source.relativize(file)).normalize();
                    if (!destination.startsWith(target) || hasSymbolicLinkBetween(target, destination.getParent())) {
                        throw new IOException("版本复制路径非法");
                    }
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                    copiedFiles[0]++;
                    return FileVisitResult.CONTINUE;
                }
            });
            log.info("继承 Vue 工程源文件：sourceVersion={}, targetVersion={}, fileCount={}, result=成功",
                    source.getFileName(), target.getFileName(), copiedFiles[0]);
        } catch (IOException exception) {
            deleteRecursively(target);
            log.warn("继承 Vue 工程源文件失败：sourceVersion={}, targetVersion={}, reason={}",
                    source.getFileName(), target.getFileName(), exception.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "复制上一版本工程文件失败", exception);
        }
    }

    public Path resolveVersionPath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "代码版本目录不存在");
        }
        Path path = codeOutputRoot.resolve(relativePath).normalize();
        if (!path.startsWith(codeOutputRoot) || hasSymbolicLinkBetween(codeOutputRoot, path)) {
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
            if (Files.isSymbolicLink(deployRoot)) {
                throw new IOException("部署根目录不能是符号链接");
            }
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
        Path directory = versionDirectory(appId, versionNo);
        deleteRecursively(directory);
        log.info("清理代码版本文件：appId={}, version={}, result=完成", appId, versionNo);
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

    /**
     * 返回受控版本目录下的相对文件名，用于对话审计和下载展示。
     * 绝不把服务器绝对路径写入数据库；符号链接和越界路径也不会被纳入结果。
     */
    public List<String> listRelativeFiles(Path directory) {
        Path normalized = directory == null ? null : directory.toAbsolutePath().normalize();
        if (normalized == null || !normalized.startsWith(codeOutputRoot)
                || !Files.isDirectory(normalized) || !isInsideRealRoot(codeOutputRoot, normalized)) {
            return List.of();
        }
        try (var paths = Files.walk(normalized)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !isIgnoredProjectPath(normalized, path))
                    .map(normalized::relativize)
                    .map(Path::normalize)
                    .filter(path -> !path.startsWith(".."))
                    .map(path -> path.toString().replace('\\', '/'))
                    .sorted()
                    .limit(100)
                    .toList();
        } catch (IOException exception) {
            log.warn("读取版本文件列表失败：directory={}", normalized, exception);
            return List.of();
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

    private boolean isIgnoredProjectPath(Path root, Path path) {
        Path relative = root.relativize(path).normalize();
        for (Path segment : relative) {
            String name = segment.toString().toLowerCase(java.util.Locale.ROOT);
            if (name.equals("node_modules") || name.equals("dist") || name.equals("build")
                    || name.equals(".git") || name.equals("target") || name.equals(".idea")
                    || name.equals(".vscode") || name.equals(".mvn") || name.equals(".env")
                    || name.startsWith(".env.") || name.equals(".npmrc") || name.equals(".yarnrc")
                    || name.equals(".yarnrc.yml") || name.endsWith(".pem") || name.endsWith(".key")
                    || name.equals("id_rsa") || name.equals("secrets") || name.equals("credentials")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSymbolicLinkBetween(Path root, Path path) {
        if (root == null || path == null) {
            return true;
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            return true;
        }
        Path current = normalizedRoot;
        if (Files.isSymbolicLink(current)) {
            return true;
        }
        for (Path segment : normalizedRoot.relativize(normalizedPath)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private void deleteRecursively(Path path) {
        // 不跟随符号链接判断存在性，失效链接也必须被清理，不能遗留在受控目录中。
        Path normalized = path == null ? null : path.toAbsolutePath().normalize();
        Path managedRoot = normalized != null && normalized.startsWith(codeOutputRoot)
                ? codeOutputRoot
                : normalized != null && normalized.startsWith(deployRoot) ? deployRoot : null;
        Path parent = normalized == null ? null : normalized.getParent();
        if (normalized == null || managedRoot == null || hasSymbolicLinkBetween(managedRoot, parent)
                || !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            if (normalized != null && managedRoot == null) {
                log.warn("拒绝清理受控根目录外的文件：path={}", normalized.getFileName());
            }
            return;
        }
        if (Files.isSymbolicLink(normalized)) {
            try {
                Files.deleteIfExists(normalized);
            } catch (IOException exception) {
                log.warn("清理符号链接失败：path={}, reason={}", normalized.getFileName(),
                        exception.getClass().getSimpleName());
            }
            return;
        }
        try (var paths = Files.walk(normalized)) {
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
