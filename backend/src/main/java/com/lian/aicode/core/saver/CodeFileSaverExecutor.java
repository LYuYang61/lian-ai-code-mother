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
    public CodeFileSaverExecutor(@Value("${app.code-output-root}") String outputRoot) {
        this(Path.of(outputRoot));
    }

    public CodeFileSaverExecutor(Path outputRoot) {
        this.htmlCodeFileSaver = new HtmlCodeFileSaverTemplate(outputRoot);
        this.multiFileCodeFileSaver = new MultiFileCodeFileSaverTemplate(outputRoot);
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
        };
    }
}
