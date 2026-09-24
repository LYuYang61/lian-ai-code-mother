package com.lian.aicode.workflow;

import com.lian.aicode.config.AiWorkflowProperties;
import com.lian.aicode.service.GenerationCancelledException;
import com.lian.aicode.workflow.ai.ImageCollectionPlanService;
import com.lian.aicode.workflow.model.ImageCollectionPlan;
import com.lian.aicode.workflow.service.WorkflowImageCollector;
import com.lian.aicode.workflow.tool.LogoGeneratorTool;
import com.lian.aicode.workflow.tool.MermaidDiagramTool;
import com.lian.aicode.workflow.tool.PexelsImageSearchTool;
import com.lian.aicode.workflow.tool.UndrawIllustrationTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证外部素材任务取消时会尽快中断 Future，不等待完整素材超时。 */
class WorkflowImageCollectorTest {

    @Test
    void cancellationInterruptsRunningImageTask() throws Exception {
        AiWorkflowProperties properties = new AiWorkflowProperties();
        properties.setEnabled(true);
        properties.getImageCollection().setEnabled(true);
        properties.getImageCollection().setTimeout(Duration.ofSeconds(10));

        ImageCollectionPlanService planService = mock(ImageCollectionPlanService.class);
        ImageCollectionPlan plan = new ImageCollectionPlan();
        plan.setContentImageTasks(List.of(new ImageCollectionPlan.ImageSearchTask("hero")));
        plan.setIllustrationTasks(List.of());
        plan.setDiagramTasks(List.of());
        plan.setLogoTasks(List.of());
        when(planService.planImageCollection("生成网页")).thenReturn(plan);

        @SuppressWarnings("unchecked")
        ObjectProvider<ImageCollectionPlanService> planProvider = mock(ObjectProvider.class);
        when(planProvider.getIfAvailable()).thenReturn(planService);

        PexelsImageSearchTool pexelsTool = mock(PexelsImageSearchTool.class);
        CountDownLatch started = new CountDownLatch(1);
        when(pexelsTool.searchContentImages("hero")).thenAnswer(invocation -> {
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw exception;
            }
            return List.of();
        });

        ExecutorService imageExecutor = Executors.newFixedThreadPool(1);
        ScheduledExecutorService canceller = Executors.newSingleThreadScheduledExecutor();
        try {
            WorkflowImageCollector collector = new WorkflowImageCollector(properties, planProvider,
                    pexelsTool, mock(UndrawIllustrationTool.class), mock(MermaidDiagramTool.class),
                    mock(LogoGeneratorTool.class), imageExecutor);
            AtomicBoolean cancelled = new AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            long startedAt = System.nanoTime();
            Thread collectionThread = Thread.startVirtualThread(() -> {
                try {
                    collector.collect("生成网页", cancelled::get);
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            canceller.schedule(() -> cancelled.set(true), 100, TimeUnit.MILLISECONDS);
            collectionThread.join(3_000);
            assertTrue(!collectionThread.isAlive(), "取消后素材收集线程应及时结束");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            assertTrue(failure.get() instanceof GenerationCancelledException);
            assertTrue(elapsedMillis < 3_000, "取消不应等待完整素材超时");
        } finally {
            canceller.shutdownNow();
            imageExecutor.shutdownNow();
        }
    }
}
