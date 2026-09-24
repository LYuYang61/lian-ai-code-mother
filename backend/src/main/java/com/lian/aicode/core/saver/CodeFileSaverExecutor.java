package com.lian.aicode.core.saver;

import com.lian.aicode.ai.model.HtmlCodeResult;
import com.lian.aicode.ai.model.MultiFileCodeResult;
import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import com.lian.aicode.model.enums.CodeGenTypeEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;

/** 根据生成类型执行对应保存策略，并在分派前校验结果类型。 */
@Component
public class CodeFileSaverExecutor {

    private final HtmlCodeFileSaverTemplate htmlCodeFileSaver;
    private final MultiFileCodeFileSaverTemplate multiFileCodeFileSaver;

    @Autowired
    public CodeFileSaverExecutor(@Value("${app.code-output-root}") String outputRoot,
                                 @Value("${app.storage.max-file-size-bytes:2097152}") long maxFileSizeBytes) {
        this(Path.of(outputRoot), maxFileSizeBytes);
    }

    public CodeFileSaverExecutor(Path outputRoot) {
        this(outputRoot, 2 * 1024 * 1024);
    }

    public CodeFileSaverExecutor(Path outputRoot, long maxFileSizeBytes) {
        this.htmlCodeFileSaver = new HtmlCodeFileSaverTemplate(outputRoot, maxFileSizeBytes);
        this.multiFileCodeFileSaver = new MultiFileCodeFileSaverTemplate(outputRoot, maxFileSizeBytes);
    }

    public File executeSaver(Object codeResult, CodeGenTypeEnum codeGenType) {
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码生成类型不能为空");
        }
        return switch (codeGenType) {
            case HTML -> {
                if (!(codeResult instanceof HtmlCodeResult result)) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "HTML 代码结果类型不正确");
                }
                yield htmlCodeFileSaver.saveCode(result);
            }
            case MULTI_FILE -> {
                if (!(codeResult instanceof MultiFileCodeResult result)) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "多文件代码结果类型不正确");
                }
                yield multiFileCodeFileSaver.saveCode(result);
            }
            case VUE_PROJECT -> throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Vue 工程由文件工具直接写入，不能使用结构化保存器");
        };
    }

    /** 将结构化结果保存到指定的应用版本目录。 */
    public File executeSaver(Object codeResult, CodeGenTypeEnum codeGenType, Path targetDirectory) {
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码生成类型不能为空");
        }
        return switch (codeGenType) {
            case HTML -> {
                if (!(codeResult instanceof HtmlCodeResult result)) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "HTML 代码结果类型不正确");
                }
                yield htmlCodeFileSaver.saveCode(result, targetDirectory);
            }
            case MULTI_FILE -> {
                if (!(codeResult instanceof MultiFileCodeResult result)) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "多文件代码结果类型不正确");
                }
                yield multiFileCodeFileSaver.saveCode(result, targetDirectory);
            }
            case VUE_PROJECT -> throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Vue 工程由文件工具直接写入，不能使用结构化保存器");
        };
    }

    /** 将质量检查重试结果安全替换到已有版本目录。 */
    public File executeSaverReplacing(Object codeResult, CodeGenTypeEnum codeGenType, Path targetDirectory) {
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码生成类型不能为空");
        }
        return switch (codeGenType) {
            case HTML -> {
                if (!(codeResult instanceof HtmlCodeResult result)) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "HTML 代码结果类型不正确");
                }
                yield htmlCodeFileSaver.replaceCode(result, targetDirectory);
            }
            case MULTI_FILE -> {
                if (!(codeResult instanceof MultiFileCodeResult result)) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "多文件代码结果类型不正确");
                }
                yield multiFileCodeFileSaver.replaceCode(result, targetDirectory);
            }
            case VUE_PROJECT -> throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Vue 工程由文件工具直接写入，不能使用结构化替换保存器");
        };
    }
}
