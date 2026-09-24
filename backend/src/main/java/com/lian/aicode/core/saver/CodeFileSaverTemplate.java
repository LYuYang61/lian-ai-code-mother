package com.lian.aicode.core.saver;

import cn.hutool.core.util.IdUtil;
import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import com.lian.aicode.model.enums.CodeGenTypeEnum;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

/**
 * 代码文件保存模板，定义校验、建目录、写文件和失败清理的统一流程。
 *
 * <p>模型只能提供文件内容，子类中的文件名是固定常量，因此不会产生路径穿越或任意文件覆盖。</p>
 */
public abstract class CodeFileSaverTemplate<T> {

    private static final long DEFAULT_MAX_FILE_SIZE_BYTES = 2 * 1024 * 1024;
    private final Path outputRoot;
    private final long maxFileSizeBytes;

    protected CodeFileSaverTemplate(Path outputRoot) {
        this(outputRoot, DEFAULT_MAX_FILE_SIZE_BYTES);
    }

    protected CodeFileSaverTemplate(Path outputRoot, long maxFileSizeBytes) {
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
        if (maxFileSizeBytes <= 0) {
            throw new IllegalArgumentException("maxFileSizeBytes must be positive");
        }
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    /** 保存代码并返回本次生成目录。 */
    public final File saveCode(T result) {
        validateInput(result);
        Path outputDirectory = null;
        try {
            Files.createDirectories(outputRoot);
            String directoryName = getCodeType().getValue() + "_" + IdUtil.getSnowflakeNextIdStr();
            outputDirectory = Files.createDirectory(outputRoot.resolve(directoryName));
            return writeFiles(result, outputDirectory);
        } catch (IOException exception) {
            cleanupPartialDirectory(outputDirectory);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "代码文件保存失败", exception);
        } catch (RuntimeException exception) {
            cleanupPartialDirectory(outputDirectory);
            throw exception;
        }
    }

    /**
     * 将代码保存到调用方预先分配的版本目录。
     *
     * <p>目录必须位于配置的输出根目录下，且只能创建一次；应用版本服务通过这个入口把
     * 生成目录与数据库中的版本号绑定，避免旧版本被覆盖。</p>
     */
    public final File saveCode(T result, Path targetDirectory) {
        validateInput(result);
        Path normalizedTarget = normalizeTargetDirectory(targetDirectory);
        Path outputDirectory = null;
        try {
            Files.createDirectories(outputRoot);
            // 版本目录按 app/{id}/v{n} 组织，先创建受控父目录，再以 createDirectory
            // 保证同一个版本不会被静默覆盖。
            Files.createDirectories(normalizedTarget.getParent());
            outputDirectory = Files.createDirectory(normalizedTarget);
            return writeFiles(result, outputDirectory);
        } catch (IOException exception) {
            cleanupPartialDirectory(outputDirectory);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "代码文件保存失败", exception);
        } catch (RuntimeException exception) {
            cleanupPartialDirectory(outputDirectory);
            throw exception;
        }
    }

    /**
     * 将新结果安全替换到已有版本目录。
     *
     * <p>工作流的质量检查失败后可能回到代码生成节点。HTML 和多文件模式使用结构化
     * 保存器，第二轮生成不能再次直接 createDirectory；先在同一父目录写临时目录，
     * 再切换目录；发生异常时尽量恢复旧目录，避免质检重试留下半套文件或覆盖到版本根目录。</p>
     */
    public final File replaceCode(T result, Path targetDirectory) {
        validateInput(result);
        Path normalizedTarget = normalizeTargetDirectory(targetDirectory);
        Path parent = normalizedTarget.getParent();
        Path temporaryDirectory = null;
        Path backupDirectory = null;
        boolean oldDirectoryMoved = false;
        boolean newDirectoryMoved = false;
        try {
            Files.createDirectories(parent);
            validateExistingTarget(normalizedTarget);
            temporaryDirectory = Files.createTempDirectory(parent,
                    "." + getCodeType().getValue().toLowerCase() + "-replace-");
            saveFiles(result, temporaryDirectory);

            if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
                backupDirectory = parent.resolve("." + normalizedTarget.getFileName()
                        + "-backup-" + UUID.randomUUID());
                Files.move(normalizedTarget, backupDirectory);
                oldDirectoryMoved = true;
            }
            Files.move(temporaryDirectory, normalizedTarget);
            newDirectoryMoved = true;
            temporaryDirectory = null;

            if (backupDirectory != null) {
                cleanupPartialDirectory(backupDirectory);
                backupDirectory = null;
            }
            return normalizedTarget.toFile();
        } catch (IOException exception) {
            restoreReplacement(normalizedTarget, backupDirectory, oldDirectoryMoved, newDirectoryMoved);
            cleanupPartialDirectory(temporaryDirectory);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "代码文件替换失败", exception);
        } catch (RuntimeException exception) {
            restoreReplacement(normalizedTarget, backupDirectory, oldDirectoryMoved, newDirectoryMoved);
            cleanupPartialDirectory(temporaryDirectory);
            throw exception;
        }
    }

    private Path normalizeTargetDirectory(Path targetDirectory) {
        if (targetDirectory == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码输出目录不能为空");
        }
        Path normalizedTarget = targetDirectory.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(outputRoot) || normalizedTarget.equals(outputRoot)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "代码输出目录超出允许范围");
        }
        return normalizedTarget;
    }

    private void validateExistingTarget(Path targetDirectory) {
        if (Files.isSymbolicLink(targetDirectory)
                || (Files.exists(targetDirectory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(targetDirectory, LinkOption.NOFOLLOW_LINKS))) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "代码输出目录不能是符号链接或普通文件");
        }
    }

    private void restoreReplacement(Path targetDirectory, Path backupDirectory,
                                    boolean oldDirectoryMoved, boolean newDirectoryMoved) {
        if (newDirectoryMoved) {
            cleanupPartialDirectory(targetDirectory);
        }
        if (oldDirectoryMoved && backupDirectory != null
                && !Files.exists(targetDirectory, LinkOption.NOFOLLOW_LINKS)
                && Files.exists(backupDirectory, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.move(backupDirectory, targetDirectory);
            } catch (IOException ignored) {
                // 原始异常优先返回；备份目录仍留在受控根目录内，便于后续人工清理。
            }
        }
    }

    private File writeFiles(T result, Path outputDirectory) throws IOException {
        saveFiles(result, outputDirectory);
        return outputDirectory.toFile();
    }

    /** 验证生成结果；具体模式可补充必填文件检查。 */
    protected void validateInput(T result) {
        if (result == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码结果对象不能为空");
        }
    }

    /** 只接受子类定义的固定文件名，不接受模型传入的路径。 */
    protected final void writeToFile(Path directory, String filename, String content) throws IOException {
        Path target = directory.resolve(filename).normalize();
        if (!target.getParent().equals(directory)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "非法的代码文件路径");
        }
        String safeContent = content == null ? "" : content;
        if (safeContent.getBytes(StandardCharsets.UTF_8).length > maxFileSizeBytes) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成文件超过大小限制");
        }
        Files.writeString(target, safeContent, StandardCharsets.UTF_8);
    }

    private void cleanupPartialDirectory(Path outputDirectory) {
        if (outputDirectory == null) {
            return;
        }
        try (var paths = Files.walk(outputDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 清理失败不覆盖原始保存异常；目录位于本次生成根目录内，后续可人工清理。
                }
            });
        } catch (IOException ignored) {
            // 同上，原始错误已通过 BusinessException 返回。
        }
    }

    protected abstract void saveFiles(T result, Path outputDirectory) throws IOException;

    protected abstract CodeGenTypeEnum getCodeType();
}
