package com.lian.aicode.workflow.service;

import com.lian.aicode.config.AiWorkflowProperties;
import com.lian.aicode.workflow.ai.ImageCollectionPlanService;
import com.lian.aicode.workflow.model.ImageCollectionPlan;
import com.lian.aicode.workflow.model.ImageResource;
import com.lian.aicode.workflow.model.WorkflowContext;
import com.lian.aicode.workflow.tool.LogoGeneratorTool;
import com.lian.aicode.workflow.tool.MermaidDiagramTool;
import com.lian.aicode.workflow.tool.PexelsImageSearchTool;
import com.lian.aicode.workflow.tool.UndrawIllustrationTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 图片计划解析和素材并发收集服务。
 *
 * <p>计划服务失败时使用确定性空计划；单个外部素材失败只影响该素材，不影响代码生成主链路。
 * 所有任务数量、并发度和等待时间都有配置上限。</p>
 */
@Slf4j
@Service
public class WorkflowImageCollector {

    private final AiWorkflowProperties properties;
    private final ObjectProvider<ImageCollectionPlanService> planServiceProvider;
    private final PexelsImageSearchTool pexelsTool;
    private final UndrawIllustrationTool undrawTool;
    private final MermaidDiagramTool mermaidTool;
    private final LogoGeneratorTool logoTool;
    private final ExecutorService executor;

    public WorkflowImageCollector(AiWorkflowProperties properties,
                                  ObjectProvider<ImageCollectionPlanService> planServiceProvider,
                                  PexelsImageSearchTool pexelsTool,
                                  UndrawIllustrationTool undrawTool,
                                  MermaidDiagramTool mermaidTool,
                                  LogoGeneratorTool logoTool,
                                  @Qualifier("aiWorkflowExecutor") ExecutorService executor) {
        this.properties = properties;
        this.planServiceProvider = planServiceProvider;
        this.pexelsTool = pexelsTool;
        this.undrawTool = undrawTool;
        this.mermaidTool = mermaidTool;
        this.logoTool = logoTool;
        this.executor = executor;
    }

    public CollectionResult collect(String prompt) {
        return collect(prompt, () -> false, null, null, null);
    }

    public CollectionResult collect(String prompt, BooleanSupplier cancelled) {
        return collect(prompt, cancelled, null, null, null);
    }

    public CollectionResult collect(String prompt,
                                     BooleanSupplier cancelled,
                                     String actorAccount,
                                     Long appId,
                                     Integer versionNo) {
        long startedAt = System.nanoTime();
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new com.lian.aicode.service.GenerationCancelledException();
        }
        if (!properties.isEnabled() || !properties.getImageCollection().isEnabled()) {
            log.info("跳过工作流图片收集：actor={}, appId={}, version={}, reason=能力未启用",
                    actorAccount, appId, versionNo);
            return new CollectionResult(new ImageCollectionPlan(), List.of());
        }
        log.info("工作流图片规划开始：actor={}, appId={}, version={}, promptLength={}",
                actorAccount, appId, versionNo, prompt == null ? 0 : prompt.length());
        ImageCollectionPlan plan = plan(prompt, cancelled);
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new com.lian.aicode.service.GenerationCancelledException();
        }
        List<Invocation> invocations = buildInvocations(plan);
        List<Invocation> scheduledInvocations = new ArrayList<>();
        List<Future<List<ImageResource>>> futures = new ArrayList<>();
        for (Invocation invocation : invocations) {
            try {
                futures.add(executor.submit(() -> {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        throw new com.lian.aicode.service.GenerationCancelledException();
                    }
                    return invocation.supplier().get();
                }));
                scheduledInvocations.add(invocation);
            } catch (RejectedExecutionException exception) {
                // 外部素材是可选能力；线程池饱和时跳过该任务，不让主代码生成失败。
                log.warn("工作流素材任务因线程池饱和而跳过：category={}, reason={}",
                        invocation.category(), exception.getClass().getSimpleName());
            }
        }

        awaitTasks(futures, cancelled, timeout(), scheduledInvocations);
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new com.lian.aicode.service.GenerationCancelledException();
        }
        List<ImageResource> resources = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            Future<List<ImageResource>> future = futures.get(i);
            if (future.isCancelled()) {
                continue;
            }
            try {
                List<ImageResource> result = future.get();
                if (result != null) {
                    resources.addAll(result);
                }
            } catch (InterruptedException exception) {
                cancelTasks(futures);
                Thread.currentThread().interrupt();
                throw new com.lian.aicode.service.GenerationCancelledException();
            } catch (ExecutionException exception) {
                log.warn("工作流图片任务失败：category={}, reason={}", scheduledInvocations.get(i).category(),
                        rootCauseName(exception));
            }
        }
        List<ImageResource> deduplicated = deduplicate(resources);
        log.info("工作流图片收集完成：actor={}, appId={}, version={}, taskCount={}, imageCount={}, durationMs={}, result=成功",
                actorAccount, appId, versionNo, scheduledInvocations.size(), deduplicated.size(),
                elapsedMillis(startedAt));
        return new CollectionResult(plan, deduplicated);
    }

    private ImageCollectionPlan plan(String prompt, BooleanSupplier cancelled) {
        ImageCollectionPlanService service = planServiceProvider.getIfAvailable();
        if (service == null) {
            log.info("工作流图片计划降级：reason=AI 图片计划服务未配置");
            return new ImageCollectionPlan();
        }
        Future<ImageCollectionPlan> future;
        try {
            future = executor.submit(() -> service.planImageCollection(prompt));
        } catch (RejectedExecutionException exception) {
            log.warn("AI 图片计划因线程池饱和而跳过：reason={}", exception.getClass().getSimpleName());
            return new ImageCollectionPlan();
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout().toMillis());
        try {
            while (true) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    future.cancel(true);
                    throw new com.lian.aicode.service.GenerationCancelledException();
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    future.cancel(true);
                    log.warn("AI 图片计划超时，跳过可选素材规划：timeoutMs={}", timeout().toMillis());
                    return new ImageCollectionPlan();
                }
                try {
                    ImageCollectionPlan result = future.get(Math.min(100,
                                    Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos))),
                            TimeUnit.MILLISECONDS);
                    return normalizePlan(result);
                } catch (TimeoutException ignored) {
                    // 轮询取消标记；在总截止时间内继续等待模型结果。
                }
            }
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new com.lian.aicode.service.GenerationCancelledException();
        } catch (ExecutionException exception) {
            log.warn("AI 图片计划失败，跳过可选素材规划：reason={}", rootCauseName(exception));
            return new ImageCollectionPlan();
        }
    }

    private List<Invocation> buildInvocations(ImageCollectionPlan plan) {
        List<Invocation> invocations = new ArrayList<>();
        if (plan == null) {
            return invocations;
        }
        if (plan.getContentImageTasks() != null) {
            plan.getContentImageTasks().forEach(task -> {
                if (task != null && StringUtils.hasText(task.query())) {
                    invocations.add(new Invocation("content", () -> pexelsTool.searchContentImages(task.query())));
                }
            });
        }
        if (plan.getIllustrationTasks() != null) {
            plan.getIllustrationTasks().forEach(task -> {
                if (task != null && StringUtils.hasText(task.query())) {
                    invocations.add(new Invocation("illustration", () -> undrawTool.searchIllustrations(task.query())));
                }
            });
        }
        if (plan.getDiagramTasks() != null) {
            plan.getDiagramTasks().forEach(task -> {
                if (task != null && StringUtils.hasText(task.mermaidCode())) {
                    invocations.add(new Invocation("diagram", () -> mermaidTool.generateMermaidDiagram(
                            task.mermaidCode(), task.description())));
                }
            });
        }
        if (plan.getLogoTasks() != null) {
            plan.getLogoTasks().forEach(task -> {
                if (task != null && StringUtils.hasText(task.description())) {
                    invocations.add(new Invocation("logo", () -> logoTool.generateLogos(task.description())));
                }
            });
        }
        return invocations;
    }

    private ImageCollectionPlan normalizePlan(ImageCollectionPlan source) {
        ImageCollectionPlan target = new ImageCollectionPlan();
        int limit = properties.getImageCollection().safeMaxPlanTasks();
        target.setContentImageTasks(source == null ? List.of() : safeList(source.getContentImageTasks(), limit));
        target.setIllustrationTasks(source == null ? List.of() : safeList(source.getIllustrationTasks(), limit));
        target.setDiagramTasks(source == null ? List.of() : safeList(source.getDiagramTasks(), limit));
        target.setLogoTasks(source == null ? List.of() : safeList(source.getLogoTasks(), limit));
        return target;
    }

    private <T> List<T> safeList(List<T> values, int limit) {
        if (values == null || limit <= 0) {
            return List.of();
        }
        return values.stream().filter(java.util.Objects::nonNull).limit(limit).toList();
    }

    private List<ImageResource> deduplicate(List<ImageResource> resources) {
        int limit = properties.getImageCollection().safeMaxImages();
        if (limit <= 0) {
            return List.of();
        }
        Map<String, ImageResource> unique = new LinkedHashMap<>();
        for (ImageResource resource : resources) {
            if (resource == null || !StringUtils.hasText(resource.getUrl())) {
                continue;
            }
            String url = resource.getUrl().trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                continue;
            }
            unique.putIfAbsent(url, ImageResource.builder()
                    .category(resource.getCategory())
                    .description(trim(resource.getDescription(), 200))
                    .url(url)
                    .build());
            if (unique.size() >= limit) {
                break;
            }
        }
        return List.copyOf(unique.values());
    }

    private Duration timeout() {
        Duration timeout = properties.getImageCollection().getTimeout();
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            return Duration.ofSeconds(45);
        }
        // 外部素材是可选增强能力，不能因为配置错误把一次工作流拖到无限等待。
        return Duration.ofMillis(Math.min(Math.max(timeout.toMillis(), 1_000), 120_000));
    }

    private void awaitTasks(List<Future<List<ImageResource>>> futures,
                            BooleanSupplier cancelled,
                            Duration timeout,
                            List<Invocation> invocations) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout.toMillis());
        try {
            while (true) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    cancelTasks(futures);
                    throw new com.lian.aicode.service.GenerationCancelledException();
                }
                if (futures.stream().allMatch(Future::isDone)) {
                    return;
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    cancelTasks(futures);
                    log.warn("工作流图片任务批次超时：taskCount={}, timeoutMs={}", invocations.size(),
                            timeout.toMillis());
                    return;
                }
                long sleepMillis = Math.max(1, Math.min(100,
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                Thread.sleep(sleepMillis);
            }
        } catch (InterruptedException exception) {
            cancelTasks(futures);
            Thread.currentThread().interrupt();
            throw new com.lian.aicode.service.GenerationCancelledException();
        }
    }

    private void cancelTasks(List<Future<List<ImageResource>>> futures) {
        futures.stream().filter(future -> !future.isDone()).forEach(future -> future.cancel(true));
    }

    private String trim(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(trimmed.length(), maxLength));
    }

    private String rootCauseName(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private record Invocation(String category, Supplier<List<ImageResource>> supplier) {
    }

    public record CollectionResult(ImageCollectionPlan plan, List<ImageResource> resources) {
    }
}
