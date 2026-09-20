package com.lian.aicode.core.saver;

import com.lian.aicode.ai.model.HtmlCodeResult;
import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import com.lian.aicode.model.enums.CodeGenTypeEnum;

import java.io.IOException;
import java.nio.file.Path;

/** 单 HTML 文件保存策略。 */
public class HtmlCodeFileSaverTemplate extends CodeFileSaverTemplate<HtmlCodeResult> {

    public HtmlCodeFileSaverTemplate(Path outputRoot) {
        super(outputRoot);
    }

    public HtmlCodeFileSaverTemplate(Path outputRoot, long maxFileSizeBytes) {
        super(outputRoot, maxFileSizeBytes);
    }

    @Override
    protected void validateInput(HtmlCodeResult result) {
        super.validateInput(result);
        if (result.getHtmlCode() == null || result.getHtmlCode().isBlank()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "HTML 代码不能为空");
        }
    }

    @Override
    protected void saveFiles(HtmlCodeResult result, Path outputDirectory) throws IOException {
        writeToFile(outputDirectory, "index.html", result.getHtmlCode());
    }

    @Override
    protected CodeGenTypeEnum getCodeType() {
        return CodeGenTypeEnum.HTML;
    }
}
