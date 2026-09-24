package com.lian.aicode.workflow.ai;

import com.lian.aicode.workflow.model.ImageCollectionPlan;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 使用 LangChain4j 结构化输出生成素材计划。
 *
 * <p>用户需求通过方法级 @UserMessage 模板的命名变量传入，避免内容中的 {{...}}
 * 被提示词模板引擎当作缺失变量解析（与质检服务同一防护，
 * 见 CodeQualityCheckService）。</p>
 */
public interface ImageCollectionPlanService {

    @SystemMessage(fromResource = "prompt/image-collection-plan-system-prompt.txt")
    @UserMessage("请为以下网站需求制定图片素材收集计划：\n\n{{userPrompt}}")
    ImageCollectionPlan planImageCollection(@V("userPrompt") String userPrompt);
}
