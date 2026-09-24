package com.lian.aicode.workflow.model;

import com.lian.aicode.service.GenerationCancelledException;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/** 单次工作流执行的非持久化运行时回调。不会放入 LangGraph checkpoint。 */
@Slf4j
public class WorkflowRuntime {

    private final Consumer<String> chunkConsumer;
    private final Consumer<WorkflowStreamMessage> eventConsumer;
    private volatile boolean cancelled;

    public WorkflowRuntime(Consumer<String> chunkConsumer,
                            Consumer<WorkflowStreamMessage> eventConsumer) {
        this.chunkConsumer = chunkConsumer;
        this.eventConsumer = eventConsumer;
    }

    /** 仅供编译图结构等不执行节点的场景使用，事件全部丢弃。 */
    public static WorkflowRuntime noop() {
        return new WorkflowRuntime(chunk -> { }, event -> { });
    }

    public void emitChunk(String chunk) {
        if (!cancelled && chunkConsumer != null && chunk != null) {
            chunkConsumer.accept(chunk);
        }
    }

    public void emitEvent(WorkflowStreamMessage event) {
        if (!cancelled && eventConsumer != null && event != null) {
            eventConsumer.accept(event);
        }
    }

    public void cancel() {
        cancelled = true;
    }

    public void checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new GenerationCancelledException();
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
