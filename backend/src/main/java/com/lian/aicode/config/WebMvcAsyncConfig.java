package com.lian.aicode.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Spring MVC 异步请求线程池配置。
 *
 * <p>SSE 生成请求本身是异步响应，但 MVC 仍需要一个执行异步分发和回调的线程池。
 * 不显式配置时会退回到每次创建线程的 SimpleAsyncTaskExecutor，高并发下容易造成线程失控。</p>
 */
@Configuration
public class WebMvcAsyncConfig {

    @Bean(name = "mvcAsyncTaskExecutor")
    public ThreadPoolTaskExecutor mvcAsyncTaskExecutor(
            @Value("${app.web.async.core-size:4}") int coreSize,
            @Value("${app.web.async.max-size:16}") int maxSize,
            @Value("${app.web.async.queue-capacity:200}") int queueCapacity) {
        int safeCoreSize = Math.max(coreSize, 1);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(safeCoreSize);
        executor.setMaxPoolSize(Math.max(maxSize, safeCoreSize));
        executor.setQueueCapacity(Math.max(queueCapacity, 1));
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("mvc-async-");
        // 线程池已满时快速失败，避免把请求线程拖成隐式无限队列。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Bean
    public WebMvcConfigurer mvcAsyncConfigurer(
            @Qualifier("mvcAsyncTaskExecutor") AsyncTaskExecutor executor,
            @Value("${app.web.async.timeout:30m}") Duration timeout) {
        return new WebMvcConfigurer() {
            @Override
            public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                configurer.setTaskExecutor(executor);
                configurer.setDefaultTimeout(Math.max(timeout.toMillis(), 1_000));
            }
        };
    }
}
