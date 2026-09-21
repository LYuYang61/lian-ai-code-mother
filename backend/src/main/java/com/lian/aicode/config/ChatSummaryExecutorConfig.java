package com.lian.aicode.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 对话摘要专用线程池。
 *
 * <p>摘要会调用外部模型，不能占用生成请求线程；同时限制并发和队列，避免大量应用同时触发摘要
 * 时耗尽本地线程或模型配额。</p>
 */
@Configuration
public class ChatSummaryExecutorConfig {

    @Bean(name = "chatSummaryExecutor")
    public ThreadPoolTaskExecutor chatSummaryExecutor(
            @Value("${app.chat-history.summary-executor-core-size:1}") int coreSize,
            @Value("${app.chat-history.summary-executor-max-size:2}") int maxSize,
            @Value("${app.chat-history.summary-executor-queue-capacity:50}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(coreSize, 1));
        executor.setMaxPoolSize(Math.max(maxSize, Math.max(coreSize, 1)));
        executor.setQueueCapacity(Math.max(queueCapacity, 1));
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("chat-summary-");
        // 摘要是异步优化项，队列满时丢弃本次触发并记录日志，不能反向阻塞代码生成请求线程。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
