package com.lian.aicode.workflow.node;

import com.lian.aicode.ai.AiCodeGenTypeRoutingServiceFactory;
import com.lian.aicode.ai.CodeGenTypeRoutingHeuristic;
import com.lian.aicode.model.enums.CodeGenTypeEnum;
import com.lian.aicode.workflow.model.WorkflowContext;
import com.lian.aicode.workflow.model.WorkflowRuntime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/** 代码类型路由节点；已有应用版本优先复用数据库类型，避免目录格式被模型改写。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouterNode {

    private final ObjectProvider<AiCodeGenTypeRoutingServiceFactory> routingServiceFactoryProvider;

    public AsyncNodeAction<MessagesState<String>> action(WorkflowRuntime runtime) {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            if (context == null) {
                throw new IllegalStateException("工作流上下文不存在");
            }
            runtime.checkCancelled();
            CodeGenTypeEnum type = context.getGenerationType();
            if (type == null) {
                // 工作流并发运行时按次创建路由服务（prototype 模型 + 结果缓存），不共享模型实例。
                AiCodeGenTypeRoutingServiceFactory factory = routingServiceFactoryProvider.getIfAvailable();
                try {
                    type = factory == null
                            ? CodeGenTypeRoutingHeuristic.choose(context.getOriginalPrompt())
                            : factory.createAiCodeGenTypeRoutingService().routeCodeGenType(context.getOriginalPrompt());
                } catch (RuntimeException exception) {
                    log.warn("工作流 AI 类型路由失败，使用确定性回退：reason={}", exception.getClass().getSimpleName());
                    type = CodeGenTypeRoutingHeuristic.choose(context.getOriginalPrompt());
                }
            }
            if (type == null) {
                type = CodeGenTypeEnum.HTML;
            }
            context.setGenerationType(type);
            context.setCurrentStep("智能路由");
            log.info("工作流节点完成：node=router, actor={}, appId={}, version={}, type={}",
                    context.getActorAccount(), context.getAppId(), context.getVersionNo(), type.getValue());
            return WorkflowContext.saveContext(context);
        });
    }
}
