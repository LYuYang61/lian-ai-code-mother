package com.lian.aicode.workflow;

import com.lian.aicode.workflow.model.WorkflowContext;
import com.lian.aicode.workflow.model.WorkflowRuntime;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.prebuilt.MessagesStateGraph;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-09-24 阶段 2 真机失败回归：SSE 回调曾被以 transient 字段挂在 WorkflowContext 上，
 * LangGraph4j 1.9.x 状态传递重建值对象后在节点执行期间丢失，工具事件全部静默丢弃，
 * 零变更守卫误杀真实有文件变更的工作流轮。此测试守护"回调属于执行参数而非状态"的架构约束。
 */
class TransientRuntimeReproTest {

    @Test
    void workflowContextMustNotCarryRuntimeCallback() {
        for (Field field : WorkflowContext.class.getDeclaredFields()) {
            assertFalse(WorkflowRuntime.class.isAssignableFrom(field.getType()),
                    "WorkflowContext 不能再持有运行时回调字段：" + field.getName()
                            + "——回调必须通过节点 action 参数注入，状态传递会重建值对象");
        }
    }

    @Test
    void runtimePassedAsActionParameterSurvivesStateTransfer() throws Exception {
        AtomicBoolean fired = new AtomicBoolean(false);
        WorkflowRuntime runtime = new WorkflowRuntime(chunk -> fired.set(true), event -> { });

        CompiledGraph<MessagesState<String>> graph = new MessagesStateGraph<String>()
                .addNode("probe", node_async(state -> {
                    // 模拟节点通过 action 参数使用回调：状态里只有业务数据。
                    runtime.emitChunk("chunk-from-node");
                    return Map.of("done", Boolean.TRUE);
                }))
                .addEdge(START, "probe")
                .addEdge("probe", END)
                .compile();

        for (NodeOutput<MessagesState<String>> ignored : graph.stream(
                GraphInput.args(Map.of()), RunnableConfig.empty())) {
            // 消费全部步骤
        }

        assertTrue(fired.get(), "通过 action 参数传入的回调在节点执行期间必须可用");
    }
}
