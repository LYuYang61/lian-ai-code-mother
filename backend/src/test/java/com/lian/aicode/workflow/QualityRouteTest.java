package com.lian.aicode.workflow;

import com.lian.aicode.config.AiWorkflowProperties;
import com.lian.aicode.model.enums.CodeGenTypeEnum;
import com.lian.aicode.workflow.model.QualityResult;
import com.lian.aicode.workflow.model.WorkflowContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 质检路由的软失败/硬失败语义测试。2026-09-24 实测：41 字符修改需求因存量代码问题
 * 重试三轮共 227 秒后整轮作废——重试耗尽后应默认软放行（构建已通过、需求可能已达成），
 * 遗留问题作为质量警告透出，而不是把版本全部作废。
 */
class QualityRouteTest {

    @Test
    void exhaustedRetriesSoftFailByDefaultAndWarn() {
        AiWorkflowProperties properties = new AiWorkflowProperties();
        properties.setMaxQualityRetries(2);
        properties.setQualityHardFail(false);
        WorkflowContext context = WorkflowContext.builder()
                .appId(1L).versionNo(1).actorAccount("tester")
                .generationType(CodeGenTypeEnum.VUE_PROJECT)
                .qualityAttempts(3)
                .qualityResult(QualityResult.builder().valid(false)
                        .errors(java.util.List.of("问题")).build())
                .build();

        assertEquals("build", CodeGenWorkflow.qualityRoute(context, properties),
                "Vue 默认软失败应放行到构建节点");
        assertNotNull(context.getQualityWarning(), "软放行必须留下质量警告");
        assertTrue(context.getQualityWarning().contains("软失败"), "警告应说明放行原因");
    }

    @Test
    void exhaustedRetriesHardFailWhenConfigured() {
        AiWorkflowProperties properties = new AiWorkflowProperties();
        properties.setMaxQualityRetries(2);
        properties.setQualityHardFail(true);
        WorkflowContext context = WorkflowContext.builder()
                .appId(1L).versionNo(1).actorAccount("tester")
                .generationType(CodeGenTypeEnum.VUE_PROJECT)
                .qualityAttempts(3)
                .qualityResult(QualityResult.builder().valid(false)
                        .errors(java.util.List.of("问题")).build())
                .build();

        assertEquals("fail", CodeGenWorkflow.qualityRoute(context, properties),
                "硬失败配置下重试耗尽应维持整轮作废");
    }

    @Test
    void nonVueSoftFailGoesToFinish() {
        AiWorkflowProperties properties = new AiWorkflowProperties();
        properties.setMaxQualityRetries(2);
        WorkflowContext context = WorkflowContext.builder()
                .appId(1L).versionNo(1).actorAccount("tester")
                .generationType(CodeGenTypeEnum.HTML)
                .qualityAttempts(3)
                .qualityResult(QualityResult.builder().valid(false)
                        .errors(java.util.List.of("问题")).build())
                .build();

        assertEquals("finish", CodeGenWorkflow.qualityRoute(context, properties),
                "HTML 软失败走 finish（无需 npm 构建）");
    }
}
