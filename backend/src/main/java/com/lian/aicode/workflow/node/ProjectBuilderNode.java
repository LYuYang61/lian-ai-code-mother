package com.lian.aicode.workflow.node;

import com.lian.aicode.core.builder.VueProjectBuilder;
import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import com.lian.aicode.model.enums.CodeGenTypeEnum;
import com.lian.aicode.workflow.model.WorkflowContext;
import com.lian.aicode.workflow.model.WorkflowRuntime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/** Vue 构建收口节点；正常情况下由 TokenStreamAdapter 已经完成构建，这里负责验证和兜底。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectBuilderNode {

    private final VueProjectBuilder projectBuilder;

    public AsyncNodeAction<MessagesState<String>> action(WorkflowRuntime runtime) {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            if (context == null) {
                throw new IllegalStateException("工作流上下文不存在");
            }
            runtime.checkCancelled();
            if (context.getGenerationType() != CodeGenTypeEnum.VUE_PROJECT) {
                context.setCurrentStep("项目构建跳过");
                return WorkflowContext.saveContext(context);
            }
            Path root = Path.of(context.getGeneratedCodeDir());
            Path dist = root.resolve("dist");
            boolean success = Files.isRegularFile(dist.resolve("index.html")) || projectBuilder.buildProject(root);
            if (!success) {
                log.warn("工作流项目构建失败：actor={}, appId={}, version={}, result=失败",
                        context.getActorAccount(), context.getAppId(), context.getVersionNo());
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "Vue 项目构建失败，请检查 Node.js 环境和生成文件");
            }
            context.setBuildResultDir(dist.toString());
            context.setCurrentStep("项目构建");
            log.info("工作流项目构建完成：actor={}, appId={}, version={}, result=成功",
                    context.getActorAccount(), context.getAppId(), context.getVersionNo());
            return WorkflowContext.saveContext(context);
        });
    }
}
