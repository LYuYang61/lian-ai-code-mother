package com.lian.aicode.service;

import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 管理应用级流式生成任务。
 *
 * <p>当前实现适合单机学习项目：取消信号保存在内存中。部署多实例后应把任务状态放进 Redis，
 * 但真正的模型订阅仍要由执行实例负责，不能只把一个 Java {@code Disposable} 放进 Redis。</p>
 */
@Component
public class GenerationTaskManager {

    private final ConcurrentMap<Long, GenerationTask> tasks = new ConcurrentHashMap<>();

    public GenerationTask start(Long appId) {
        GenerationTask task = new GenerationTask();
        if (tasks.putIfAbsent(appId, task) != null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "该应用已有生成任务，请先停止或等待完成");
        }
        return task;
    }

    public boolean cancel(Long appId) {
        GenerationTask task = tasks.get(appId);
        return task != null && task.cancel();
    }

    public void finish(Long appId, GenerationTask task) {
        tasks.remove(appId, task);
    }

    public boolean isGenerating(Long appId) {
        return tasks.containsKey(appId);
    }

    public static final class GenerationTask {
        private final Sinks.One<Boolean> cancelSink = Sinks.one();
        private volatile boolean cancelled;

        public Mono<Boolean> cancelSignal() {
            return cancelSink.asMono();
        }

        public boolean cancel() {
            cancelled = true;
            // takeUntilOther 需要收到 onNext；仅发送 onComplete 不会触发上游取消。
            cancelSink.tryEmitValue(true);
            return true;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }
}
