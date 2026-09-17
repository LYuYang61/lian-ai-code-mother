package com.lian.aicode.core.saver;

import com.lian.aicode.ai.model.MultiFileCodeResult;
import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import com.lian.aicode.model.enums.CodeGenTypeEnum;

import java.io.IOException;
import java.nio.file.Path;

/** HTML、CSS、JavaScript 三文件保存策略。 */
public class MultiFileCodeFileSaverTemplate extends CodeFileSaverTemplate<MultiFileCodeResult> {

    public MultiFileCodeFileSaverTemplate(Path outputRoot) {
        super(outputRoot);
    }

    @Override
    protected void validateInput(MultiFileCodeResult result) {
        super.validateInput(result);
        if (result.getHtmlCode() == null || result.getHtmlCode().isBlank()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "多文件模式的 HTML 代码不能为空");
        }
    }

    @Override
    protected void saveFiles(MultiFileCodeResult result, Path outputDirectory) throws IOException {
        writeToFile(outputDirectory, "index.html", result.getHtmlCode());
        // 即使没有样式或交互，也创建固定文件，保证生成目录结构稳定。
        writeToFile(outputDirectory, "style.css", result.getCssCode());
        writeToFile(outputDirectory, "script.js", result.getJsCode());
    }

    @Override
    protected CodeGenTypeEnum getCodeType() {
        return CodeGenTypeEnum.MULTI_FILE;
    }
}
