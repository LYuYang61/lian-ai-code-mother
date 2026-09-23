package com.lian.aicode.ai;

import com.lian.aicode.model.enums.CodeGenTypeEnum;
import dev.langchain4j.service.SystemMessage;

/** 使用 LangChain4j AI Service 根据初始需求选择代码生成模式。 */
public interface AiCodeGenTypeRoutingService {

    @SystemMessage(fromResource = "prompt/codegen-routing-system-prompt.txt")
    CodeGenTypeEnum routeCodeGenType(String userPrompt);
}
