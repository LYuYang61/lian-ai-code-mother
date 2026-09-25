package com.lian.aicode.config;

import dev.langchain4j.http.client.spring.restclient.SpringRestClient;
import dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilder;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.Data;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 流式代码生成模型的原型（prototype）配置。
 *
 * <p>第十期起代码生成工厂每次构建 AI Service 时都从这里获取全新的模型实例，
 * 不同应用/任务的生成请求不再共享同一个 {@code StreamingChatModel}，与教程
 * “哪儿阻塞就针对哪儿解决”的多例方案对齐；字段集合必须与 application.yml 的
 * {@code langchain4j.open-ai.streaming-chat-model} 保持一致（含思考模式显式禁用的
 * custom-parameters），修改任一侧时要同步检查另一侧。</p>
 *
 * <p>与教程实现的一个重要差异：所有原型模型共享同一个流式 HTTP 读线程池
 * {@link #streamingHttpTaskExecutor()}。SpringRestClient 的默认执行器每个实例常驻一个
 * 核心线程，若按教程每次 new 模型，长期运行会随任务数累积空闲线程；共享池用
 * {@code queueCapacity=0 + maxPoolSize 无上限} 的“每流一线程、空闲回收”语义，
 * 既不限制并发生成数量，也不会泄漏线程。</p>
 */
@Data
@Configuration
@ConditionalOnProperty(name = {
        "langchain4j.open-ai.chat-model.api-key",
        "langchain4j.open-ai.streaming-chat-model.api-key"
})
@ConfigurationProperties(prefix = "langchain4j.open-ai.streaming-chat-model")
public class StreamingChatModelConfig {

    private String baseUrl;
    private String apiKey;
    private String modelName;
    private Integer maxTokens;
    private Double temperature;
    private Boolean returnThinking;
    private String reasoningEffort;
    /** 绑定 custom-parameters.* 配置（当前用于 DeepSeek 思考模式显式禁用）。 */
    private Map<String, Object> customParameters = new LinkedHashMap<>();
    private Boolean logRequests = false;
    private Boolean logResponses = false;

    /** 所有原型流式模型共享的 SSE 读取线程池：每条流一个工作线程，空闲 60 秒回收。 */
    @Bean(name = "streamingHttpTaskExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor streamingHttpTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(0);
        executor.setMaxPoolSize(Integer.MAX_VALUE);
        // 队列容量 0 时 Spring 使用 SynchronousQueue：读流任务不排队，直接获得独立线程。
        executor.setQueueCapacity(0);
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("langchain4j-stream-http-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean
    @Scope("prototype")
    public StreamingChatModel streamingChatModelPrototype(
            @Qualifier("streamingHttpTaskExecutor") AsyncTaskExecutor streamingHttpTaskExecutor) {
        // 显式注入共享执行器并关闭默认执行器创建，避免每个模型实例常驻线程。
        // SpringRestClientBuilder 本身实现了 HttpClientBuilder，模型构建时才会调用其 build()。
        SpringRestClientBuilder httpClientBuilder = SpringRestClient.builder()
                .streamingRequestExecutor(streamingHttpTaskExecutor)
                .createDefaultStreamingRequestExecutor(false);
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .returnThinking(returnThinking)
                .reasoningEffort(reasoningEffort)
                .customParameters(customParameters)
                .httpClientBuilder(httpClientBuilder)
                .logRequests(Boolean.TRUE.equals(logRequests))
                .logResponses(Boolean.TRUE.equals(logResponses))
                .build();
    }
}
