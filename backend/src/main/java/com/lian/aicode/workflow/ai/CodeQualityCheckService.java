package com.lian.aicode.workflow.ai;

import com.lian.aicode.workflow.model.QualityResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 使用 LangChain4j 结构化输出执行生成代码的质量检查。
 *
 * <p>代码内容必须通过方法级 @UserMessage 模板的命名变量传入：无注解的单 String 参数
 * （或参数级 @UserMessage）会被 LangChain4j 当作提示词模板渲染，工程代码里的 Vue 插值
 * （如 {{layer.tag}}）会被识别为缺失的模板变量并抛 IllegalArgumentException
 * （2026-09-24 实测，曾被 fail-open 掩盖）。变量值中的花括号不会被模板引擎扫描。</p>
 */
public interface CodeQualityCheckService {

    @SystemMessage(fromResource = "prompt/code-quality-check-system-prompt.txt")
    @UserMessage("请检查以下前端工程代码并返回质量检查结果：\n\n{{codeContent}}")
    QualityResult checkCodeQuality(@V("codeContent") String codeContent);
}
