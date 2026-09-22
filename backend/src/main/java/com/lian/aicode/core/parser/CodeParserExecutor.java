package com.lian.aicode.core.parser;

import com.lian.aicode.ai.model.HtmlCodeResult;
import com.lian.aicode.ai.model.MultiFileCodeResult;
import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import com.lian.aicode.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

/** 根据代码生成类型选择解析策略的执行器。 */
@Component
public class CodeParserExecutor {

    private final CodeParser<HtmlCodeResult> htmlCodeParser = new HtmlCodeParser();
    private final CodeParser<MultiFileCodeResult> multiFileCodeParser = new MultiFileCodeParser();

    /**
     * 执行解析。返回值是两种结果对象之一，由保存执行器按同一个枚举继续分派。
     */
    public Object executeParser(String codeContent, CodeGenTypeEnum codeGenType) {
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码生成类型不能为空");
        }
        return switch (codeGenType) {
            case HTML -> htmlCodeParser.parseCode(codeContent);
            case MULTI_FILE -> multiFileCodeParser.parseCode(codeContent);
            case VUE_PROJECT -> throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Vue 工程使用工具调用写入文件，不能解析为 Markdown 代码块");
        };
    }
}
