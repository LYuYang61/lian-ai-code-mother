package com.lian.aicode.service;

import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Set;
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
    /** 删除与新生成任务共用同一把进程内锁，避免删除过程中又创建新版本。 */
    private final Set<Long> deletingApps = ConcurrentHashMap.newKeySet();

    public synchronized GenerationTask start(Long appId) {
        if (deletingApps.contains(appId)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "应用正在删除，请稍后重试");
        }
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

    public synchronized void finish(Long appId, GenerationTask task) {
        tasks.remove(appId, task);
    }

    /**
     * 尝试进入应用删除临界区。
     *
     * <p>生成任务一旦开始，删除接口必须等待其自然结束或由用户明确停止后再重试，
     * 不能一边删除数据库和文件，一边让旧任务继续写入新版本。</p>
     */
    public synchronized boolean beginDelete(Long appId) {
        if (tasks.containsKey(appId) || deletingApps.contains(appId)) {
            return false;
        }
        deletingApps.add(appId);
        return true;
    }

    public synchronized void finishDelete(Long appId) {
        deletingApps.remove(appId);
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
