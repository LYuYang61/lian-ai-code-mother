package com.lian.aicode.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 工作流外部素材任务的有界线程池；模型和第三方 IO 不使用无限制公共线程池。 */
@Slf4j
@Configuration
public class AiWorkflowExecutorConfiguration {

    @Bean(name = "aiWorkflowExecutor", destroyMethod = "shutdown")
    public ExecutorService aiWorkflowExecutor(AiWorkflowProperties properties) {
        int parallelism = properties.getImageCollection().safeParallelism();
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "ai-workflow-image-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        // 每类最多 20 个任务、四类素材共 80 个任务；队列显式设上限，避免未来放宽任务数后无限堆积。
        int queueCapacity = Math.max(80, parallelism);
        log.info("初始化 AI 工作流素材线程池：parallelism={}, queueCapacity={}, result=成功",
                parallelism, queueCapacity);
        return new ThreadPoolExecutor(parallelism, parallelism, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
    }
}
