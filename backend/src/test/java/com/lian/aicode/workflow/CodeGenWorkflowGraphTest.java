package com.lian.aicode.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 只验证状态图能够按当前 LangGraph4j 版本编译，不调用外部模型或素材服务。 */
@SpringBootTest
@ActiveProfiles("test")
class CodeGenWorkflowGraphTest {

    @Autowired
    private CodeGenWorkflow workflow;

    @Test
    void compilesExpectedNodesAndQualityBranches() {
        String graph = workflow.graphMermaid();
        assertTrue(graph.contains("image_collection"));
        assertTrue(graph.contains("code_quality_check"));
        assertTrue(graph.contains("project_builder"));
        assertTrue(graph.contains("retry"));
    }
}
