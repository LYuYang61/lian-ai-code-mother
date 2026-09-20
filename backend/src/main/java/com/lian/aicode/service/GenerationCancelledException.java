package com.lian.aicode.service;

/**
 * 生成任务被用户主动停止时使用的内部流信号。
 *
 * <p>不能把取消当成普通完成，否则控制器会发送 {@code done}，前端会误报“生成完成”。</p>
 */
public class GenerationCancelledException extends RuntimeException {

    public GenerationCancelledException() {
        super("生成已取消");
    }
}
