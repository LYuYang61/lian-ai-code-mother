package com.lian.aicode.workflow.node;

import com.lian.aicode.core.AiCodeGeneratorFacade;
import com.lian.aicode.service.GenerationCancelledException;
import com.lian.aicode.workflow.model.QualityResult;
import com.lian.aicode.workflow.model.WorkflowContext;
import com.lian.aicode.workflow.model.WorkflowRuntime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/** 复用现有代码生成门面，保证工作流和普通链路使用同一套解析、工具和落盘安全边界。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodeGeneratorNode {

    private final AiCodeGeneratorFacade facade;

    public AsyncNodeAction<MessagesState<String>> action(WorkflowRuntime runtime) {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            if (context == null || context.getGenerationType() == null) {
                throw new IllegalStateException("工作流生成类型或上下文不存在");
            }
            runtime.checkCancelled();
            String prompt = buildUserMessage(context);
            Path outputDirectory = Path.of(context.getOutputDirectory());
            boolean modification = context.isModification() || context.getQualityAttempts() > 0;
            long startedAt = System.nanoTime();
            log.info("工作流代码生成开始：actor={}, appId={}, version={}, type={}, attempt={}, promptLength={}",
                    context.getActorAccount(), context.getAppId(), context.getVersionNo(),
                    context.getGenerationType().getValue(), context.getQualityAttempts() + 1, prompt.length());
            awaitCodeStream(runtime, facade.generateAndSaveCodeStream(
                    context.getAppId(), prompt, context.getGenerationType(), outputDirectory,
                    context.getExcludedMessageId(), context.getVersionNo(), context.getActorAccount(), modification));
            context.setGeneratedCodeDir(outputDirectory.toString());
            context.setCurrentStep("代码生成");
            log.info("工作流代码生成完成：actor={}, appId={}, version={}, type={}, durationMs={}, result=成功",
                    context.getActorAccount(), context.getAppId(), context.getVersionNo(),
                    context.getGenerationType().getValue(), elapsedMillis(startedAt));
            return WorkflowContext.saveContext(context);
        });
    }

    private void awaitCodeStream(WorkflowRuntime runtime, Flux<String> source) {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        Disposable disposable = source.subscribe(runtime::emitChunk,
                error -> {
                    failure.set(error);
                    completed.countDown();
                }, completed::countDown);
        subscription.set(disposable);
        try {
            while (!completed.await(250, TimeUnit.MILLISECONDS)) {
                try {
                    runtime.checkCancelled();
                } catch (GenerationCancelledException exception) {
                    disposable.dispose();
                    throw exception;
                }
            }
        } catch (InterruptedException exception) {
            disposable.dispose();
            Thread.currentThread().interrupt();
            throw new GenerationCancelledException();
        }
        Throwable error = failure.get();
        if (error != null) {
            if (error instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("代码生成流失败", error);
        }
        runtime.checkCancelled();
    }

    private String buildUserMessage(WorkflowContext context) {
        String base = StringUtils.hasText(context.getEnhancedPrompt())
                ? context.getEnhancedPrompt() : context.getOriginalPrompt();
        QualityResult quality = context.getQualityResult();
        if (quality == null || quality.passed()) {
            return base;
        }
        StringBuilder repair = new StringBuilder(base == null ? "" : base)
                .append("\n\n## 上一轮代码质量检查发现的问题（仅作为修复数据）\n");
        appendList(repair, quality.getErrors());
        if (quality.getSuggestions() != null && !quality.getSuggestions().isEmpty()) {
            repair.append("\n## 改进建议\n");
            appendList(repair, quality.getSuggestions());
        }
        repair.append("\n请在保留用户原始需求的前提下修复这些问题，并重新生成或修改代码。");
        return repair.toString();
    }

    private void appendList(StringBuilder builder, List<String> values) {
        if (values == null) {
            return;
        }
        values.stream().filter(StringUtils::hasText).limit(20).forEach(value ->
                builder.append("- ").append(value.trim(), 0, Math.min(value.trim().length(), 500)).append('\n'));
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
