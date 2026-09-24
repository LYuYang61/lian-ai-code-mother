package com.lian.aicode.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lian.aicode.config.AiWorkflowProperties;
import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import com.lian.aicode.service.GenerationCancelledException;
import com.lian.aicode.workflow.model.QualityResult;
import com.lian.aicode.workflow.model.WorkflowContext;
import com.lian.aicode.workflow.model.WorkflowRequest;
import com.lian.aicode.workflow.model.WorkflowRuntime;
import com.lian.aicode.workflow.model.WorkflowStreamMessage;
import com.lian.aicode.workflow.node.CodeGeneratorNode;
import com.lian.aicode.workflow.node.CodeQualityCheckNode;
import com.lian.aicode.workflow.node.ImageCollectionNode;
import com.lian.aicode.workflow.node.PromptEnhancerNode;
import com.lian.aicode.workflow.node.ProjectBuilderNode;
import com.lian.aicode.workflow.node.RouterNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.prebuilt.MessagesStateGraph;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.List;
import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;

/**
 * 第九期代码生成工作流。
 *
 * <p>图负责状态转移和有限重试，现有 {@code AiCodeGeneratorFacade} 负责真正的模型流、工具调用、
 * 解析和落盘。这样工作流扩展不会复制已有安全边界，也不会让两个生成器分别维护版本状态。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodeGenWorkflow {

    private final AiWorkflowProperties properties;
    private final ObjectMapper objectMapper;
    private final ImageCollectionNode imageCollectionNode;
    private final PromptEnhancerNode promptEnhancerNode;
    private final RouterNode routerNode;
    private final CodeGeneratorNode codeGeneratorNode;
    private final CodeQualityCheckNode codeQualityCheckNode;
    private final ProjectBuilderNode projectBuilderNode;

    /**
     * 编译工作流图；当前每次请求编译一次，便于未来按租户或配置构造不同图。
     *
     * <p>runtime 是本次执行的 SSE 回调，必须作为参数注入节点 action，不能放进
     * WorkflowContext：LangGraph4j 1.9.x 状态传递会重建值对象，transient 回调在
     * 节点执行期间为 null，曾导致工具事件全部静默丢弃（2026-09-24 实测）。</p>
     */
    public CompiledGraph<MessagesState<String>> createWorkflow(WorkflowRuntime runtime) {
        try {
            return new MessagesStateGraph<String>()
                    .addNode("image_collection", imageCollectionNode.action(runtime))
                    .addNode("prompt_enhancer", promptEnhancerNode.action(runtime))
                    .addNode("router", routerNode.action(runtime))
                    .addNode("code_generator", codeGeneratorNode.action(runtime))
                    .addNode("code_quality_check", codeQualityCheckNode.action(runtime))
                    .addNode("project_builder", projectBuilderNode.action(runtime))
                    .addEdge(START, "image_collection")
                    .addEdge("image_collection", "prompt_enhancer")
                    .addEdge("prompt_enhancer", "router")
                    .addEdge("router", "code_generator")
                    .addEdge("code_generator", "code_quality_check")
                    .addConditionalEdges("code_quality_check", edge_async(this::routeAfterQualityCheck), Map.of(
                            "retry", "code_generator",
                            "build", "project_builder",
                            "finish", END,
                            "fail", END))
                    .addEdge("project_builder", END)
                    .compile();
        } catch (GraphStateException exception) {
            log.error("创建 AI 工作流失败：reason={}", exception.getClass().getSimpleName(), exception);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 工作流创建失败", exception);
        }
    }

    /**
     * 通过 Reactor Flux 接入已有应用 SSE。工作流事件和现有代码/工具事件共用一条通道，
     * 取消时会先取消运行时，再由下游任务管理器收口版本状态。
     */
    public Flux<String> executeWorkflowWithFlux(WorkflowRequest request) {
        if (!properties.isEnabled()) {
            return Flux.error(new BusinessException(ErrorCode.OPERATION_ERROR, "AI 工作流未启用"));
        }
        return Flux.create(sink -> {
            WorkflowContext context = createContext(request);
            WorkflowRuntime runtime = new WorkflowRuntime(sink::next,
                    event -> emitEvent(sink, event));
            sink.onCancel(runtime::cancel);
            Thread.startVirtualThread(() -> runGraph(request, context, runtime, sink));
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    /** 返回当前图的 Mermaid 表示，便于开发期排查状态转移，不暴露业务提示词。 */
    public String graphMermaid() {
        GraphRepresentation representation = createWorkflow(WorkflowRuntime.noop())
                .getGraph(GraphRepresentation.Type.MERMAID);
        return representation.content();
    }

    private void runGraph(WorkflowRequest request,
                          WorkflowContext context,
                          WorkflowRuntime runtime,
                          FluxSink<String> sink) {
        long startedAt = System.nanoTime();
        try {
            emitEvent(sink, WorkflowStreamMessage.builder()
                    .type("workflow_start")
                    .step("初始化")
                    .message("开始执行 AI 工作流")
                    .promptLength(request.getPrompt() == null ? 0 : request.getPrompt().length())
                    .build());
            log.info("AI 工作流开始：actor={}, appId={}, version={}, type={}, promptLength={}",
                    request.getActorAccount(), request.getAppId(), request.getVersionNo(),
                    request.getGenerationType() == null ? "待路由" : request.getGenerationType().getValue(),
                    request.getPrompt() == null ? 0 : request.getPrompt().length());

            CompiledGraph<MessagesState<String>> workflow = createWorkflow(runtime);
            int stepNumber = 0;
            for (NodeOutput<MessagesState<String>> output : workflow.stream(
                    GraphInput.args(Map.of(WorkflowContext.WORKFLOW_CONTEXT_KEY, context)),
                    RunnableConfig.empty())) {
                runtime.checkCancelled();
                WorkflowContext current = WorkflowContext.getContext(output.state());
                if (current == null) {
                    continue;
                }
                stepNumber++;
                emitEvent(sink, WorkflowStreamMessage.builder()
                        .type("workflow_step")
                        .step(current.getCurrentStep())
                        .message("工作流节点完成")
                        .attempt(current.getQualityAttempts())
                        .imageCount(current.getImageList() == null ? 0 : current.getImageList().size())
                        .data(Map.of("stepNumber", stepNumber))
                        .build());
                context = current;
            }
            if (context.getErrorMessage() != null && !context.getErrorMessage().isBlank()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, context.getErrorMessage());
            }
            String completionMessage = context.getQualityWarning() == null
                    ? "AI 工作流执行完成" : "AI 工作流执行完成；" + context.getQualityWarning();
            emitEvent(sink, WorkflowStreamMessage.builder()
                    .type("workflow_completed")
                    .step(context.getCurrentStep())
                    .message(completionMessage)
                    .attempt(context.getQualityAttempts())
                    .build());
            log.info("AI 工作流完成：actor={}, appId={}, version={}, durationMs={}, result=成功",
                    request.getActorAccount(), request.getAppId(), request.getVersionNo(), elapsedMillis(startedAt));
            if (!sink.isCancelled()) {
                sink.complete();
            }
        } catch (GenerationCancelledException exception) {
            log.info("AI 工作流取消：actor={}, appId={}, version={}, durationMs={}",
                    request.getActorAccount(), request.getAppId(), request.getVersionNo(), elapsedMillis(startedAt));
            if (!sink.isCancelled()) {
                sink.error(exception);
            }
        } catch (Throwable throwable) {
            log.warn("AI 工作流失败：actor={}, appId={}, version={}, reason={}, durationMs={}",
                    request.getActorAccount(), request.getAppId(), request.getVersionNo(),
                    throwable.getClass().getSimpleName(), elapsedMillis(startedAt));
            emitEvent(sink, WorkflowStreamMessage.builder()
                    .type("workflow_error")
                    .step(context.getCurrentStep())
                    .message("AI 工作流执行失败")
                    .data(Map.of("reason", throwable.getClass().getSimpleName()))
                    .build());
            if (!sink.isCancelled()) {
                sink.error(throwable instanceof RuntimeException runtimeException
                        ? runtimeException : new IllegalStateException("AI 工作流执行失败", throwable));
            }
        }
    }

    private WorkflowContext createContext(WorkflowRequest request) {
        return WorkflowContext.builder()
                .appId(request.getAppId())
                .versionNo(request.getVersionNo())
                .excludedMessageId(request.getExcludedMessageId())
                .actorAccount(request.getActorAccount())
                .outputDirectory(request.getOutputDirectory().toAbsolutePath().normalize().toString())
                .baselineDirectory(request.getBaselineDirectory() == null ? null
                        : request.getBaselineDirectory().toAbsolutePath().normalize().toString())
                .currentStep("初始化")
                .originalPrompt(request.getPrompt())
                .generationType(request.getGenerationType())
                .modification(request.isModification())
                .imageList(List.of())
                .qualityAttempts(0)
                .build();
    }

    private String routeAfterQualityCheck(MessagesState<String> state) {
        return qualityRoute(WorkflowContext.getContext(state), properties);
    }

    /** 包级可见以便单测软失败/硬失败分支。 */
    static String qualityRoute(WorkflowContext context, AiWorkflowProperties properties) {
        if (context == null) {
            return "fail";
        }
        QualityResult result = context.getQualityResult();
        if (result == null || !result.passed()) {
            int maxRetries = properties.safeMaxQualityRetries();
            if (context.getQualityAttempts() <= maxRetries) {
                log.warn("工作流代码质检未通过，准备重试：actor={}, appId={}, version={}, attempt={}, maxRetries={}",
                        context.getActorAccount(), context.getAppId(), context.getVersionNo(),
                        context.getQualityAttempts(), maxRetries);
                return "retry";
            }
            if (!properties.isQualityHardFail()) {
                // 软失败：能走到质检说明生成和构建链路已通过；放行版本，遗留问题作为警告透出。
                context.setQualityWarning("代码质量检查未通过（已重试 " + context.getQualityAttempts()
                        + " 轮），已按软失败配置放行，建议结合预览确认效果");
                log.warn("工作流代码质检未通过但软放行：actor={}, appId={}, version={}, attempts={}, result=警告放行",
                        context.getActorAccount(), context.getAppId(), context.getVersionNo(),
                        context.getQualityAttempts());
                return context.getGenerationType() == com.lian.aicode.model.enums.CodeGenTypeEnum.VUE_PROJECT
                        ? "build" : "finish";
            }
            context.setErrorMessage("代码质量检查未通过，已达到最大修复次数，请调整需求后重试");
            log.warn("工作流代码质检终止：actor={}, appId={}, version={}, attempts={}, result=失败",
                    context.getActorAccount(), context.getAppId(), context.getVersionNo(),
                    context.getQualityAttempts());
            return "fail";
        }
        return context.getGenerationType() == com.lian.aicode.model.enums.CodeGenTypeEnum.VUE_PROJECT
                ? "build" : "finish";
    }

    private void emitEvent(FluxSink<String> sink, WorkflowStreamMessage event) {
        if (sink.isCancelled() || event == null) {
            return;
        }
        try {
            sink.next(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            log.warn("编码工作流事件失败：type={}, reason={}", event.getType(),
                    exception.getClass().getSimpleName());
            sink.error(new IllegalStateException("工作流事件编码失败", exception));
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
