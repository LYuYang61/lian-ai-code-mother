package com.lian.aicode.core.saver;

import cn.hutool.core.util.IdUtil;
import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import com.lian.aicode.model.enums.CodeGenTypeEnum;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 代码文件保存模板，定义校验、建目录、写文件和失败清理的统一流程。
 *
 * <p>模型只能提供文件内容，子类中的文件名是固定常量，因此不会产生路径穿越或任意文件覆盖。</p>
 */
public abstract class CodeFileSaverTemplate<T> {

    private final Path outputRoot;

    protected CodeFileSaverTemplate(Path outputRoot) {
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
    }

    /** 保存代码并返回本次生成目录。 */
    public final File saveCode(T result) {
        validateInput(result);
        Path outputDirectory = null;
        try {
            Files.createDirectories(outputRoot);
            String directoryName = getCodeType().getValue() + "_" + IdUtil.getSnowflakeNextIdStr();
            outputDirectory = Files.createDirectory(outputRoot.resolve(directoryName));
            saveFiles(result, outputDirectory);
            return outputDirectory.toFile();
        } catch (IOException exception) {
            cleanupPartialDirectory(outputDirectory);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "代码文件保存失败", exception);
        } catch (RuntimeException exception) {
            cleanupPartialDirectory(outputDirectory);
            throw exception;
        }
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
        Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
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
