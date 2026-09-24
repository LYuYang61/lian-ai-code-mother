package com.lian.aicode.workflow.node;

import com.lian.aicode.workflow.model.WorkflowContext;
import com.lian.aicode.workflow.model.WorkflowRuntime;
import com.lian.aicode.workflow.service.WorkflowImageCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.stereotype.Component;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/** 图片计划和素材收集节点。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageCollectionNode {

    private final WorkflowImageCollector imageCollector;

    public AsyncNodeAction<MessagesState<String>> action(WorkflowRuntime runtime) {
        return node_async(state -> {
            WorkflowContext context = requireContext(state);
            runtime.checkCancelled();
            WorkflowImageCollector.CollectionResult result = imageCollector.collect(
                    context.getOriginalPrompt(), runtime::isCancelled,
                    context.getActorAccount(), context.getAppId(), context.getVersionNo());
            runtime.checkCancelled();
            context.setImageCollectionPlan(result.plan());
            context.setImageList(result.resources());
            context.setCurrentStep("图片收集");
            log.info("工作流节点完成：node=image_collection, actor={}, appId={}, version={}, imageCount={}",
                    context.getActorAccount(), context.getAppId(), context.getVersionNo(), result.resources().size());
            return WorkflowContext.saveContext(context);
        });
    }

    private WorkflowContext requireContext(MessagesState<String> state) {
        WorkflowContext context = WorkflowContext.getContext(state);
        if (context == null) {
            throw new IllegalStateException("工作流上下文不存在");
        }
        return context;
    }
}
