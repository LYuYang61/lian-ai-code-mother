package com.lian.aicode.core;

import com.lian.aicode.ai.AiCodeGenTypeRoutingServiceFactory;
import com.lian.aicode.ai.AiCodeGeneratorService;
import com.lian.aicode.ai.AiCodeGeneratorServiceFactory;
import com.lian.aicode.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第十期“AI 并发调用”的真实模型验收测试（对应教程第 11 期的并发验证环节）。
 *
 * <p>使用按次创建的原型模型服务，通过虚拟线程并发发起两条流式生成；并发成立的判据是
 * 两条流的“首个片段时间点”区间重叠：若底层把请求串行化，第二条流的首个片段只可能出现在
 * 第一条流完全结束之后。串行/并发的结论以本测试输出为准，写入第十期学习文档时须如实记录。</p>
 *
 * <p>安全开关与 {@link AiCodeGeneratorRealCallTest} 相同：只有环境变量
 * {@code AI_REAL_CALL_TEST=true} 时执行，常规回归永远不产生模型费用。</p>
 */
@Timeout(300)
@EnabledIfEnvironmentVariable(named = "AI_REAL_CALL_TEST", matches = "true")
@SpringBootTest(properties = {
        "langchain4j.open-ai.chat-model.api-key=${DEEPSEEK_API_KEY}",
        "langchain4j.open-ai.streaming-chat-model.api-key=${DEEPSEEK_API_KEY}"
})
@ActiveProfiles("test")
class AiConcurrentRealCallTest {

    @Autowired
    private AiCodeGeneratorServiceFactory serviceFactory;

    @Autowired
    private AiCodeGenTypeRoutingServiceFactory routingServiceFactory;

    /** 单条流的时间采样：首个片段到达时间与流结束时间（纳秒时间戳）。 */
    private record StreamTiming(long startedAt, AtomicLong firstChunkAt, AtomicLong endAt) {

        void awaitCompletion() {
            // 简单轮询等待：验收测试只关心区间重叠，不追求响应式等待的精确性。
            while (endAt.get() == 0) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Test
    void twoStreamingCallsOverlapWithPrototypeModels() throws Exception {
        StreamTiming first = startStreamingTask("一个红色大标题显示 Hello World 的简单页面");
        StreamTiming second = startStreamingTask("一个蓝色标题带留言输入框的简单页面");

        Thread firstThread = Thread.ofVirtual().start(first::awaitCompletion);
        Thread secondThread = Thread.ofVirtual().start(second::awaitCompletion);
        firstThread.join();
        secondThread.join();

        logTiming("流一", first);
        logTiming("流二", second);
        assertTrue(first.endAt().get() > 0 && second.endAt().get() > 0, "两条流都应正常结束");
        long overlapMillis = Math.min(first.endAt().get(), second.endAt().get())
                - Math.max(first.firstChunkAt().get(), second.firstChunkAt().get());
        boolean overlapped = first.firstChunkAt().get() < second.endAt().get()
                && second.firstChunkAt().get() < first.endAt().get();
        // 串行场景下该断言必然失败：第二条流的首个片段不可能早于第一条流结束。
        assertTrue(overlapped, "两条流的首末片段区间应重叠（重叠 " + overlapMillis + " ms），"
                + "若该断言失败说明模型调用被串行化");
    }

    @Test
    void concurrentRoutingCallsAllReturnValidType() throws Exception {
        List<Thread> threads = new ArrayList<>();
        List<CodeGenTypeEnum> results = new ArrayList<>();
        List<RuntimeException> failures = new ArrayList<>();
        Object lock = new Object();
        for (int index = 0; index < 3; index++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    CodeGenTypeEnum type = routingServiceFactory.createAiCodeGenTypeRoutingService()
                            .routeCodeGenType("做一个包含登录页和列表页的管理系统");
                    synchronized (lock) {
                        results.add(type);
                    }
                } catch (RuntimeException exception) {
                    synchronized (lock) {
                        failures.add(exception);
                    }
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertTrue(failures.isEmpty(), "并发路由不应出现异常：" + failures);
        assertTrue(results.size() == 3 && results.stream().allMatch(type -> type != null),
                "并发路由应全部返回有效类型：" + results);
    }

    private StreamTiming startStreamingTask(String prompt) {
        AtomicLong firstChunkAt = new AtomicLong(0);
        AtomicLong endAt = new AtomicLong(0);
        long startedAt = System.nanoTime();
        AiCodeGeneratorService service = serviceFactory.getForStatelessTask();
        service.generateHtmlCodeStream(prompt)
                .doOnNext(chunk -> firstChunkAt.compareAndSet(0, System.nanoTime()))
                .collectList()
                .doFinally(signal -> endAt.set(System.nanoTime()))
                .subscribe();
        return new StreamTiming(startedAt, firstChunkAt, endAt);
    }

    private void logTiming(String label, StreamTiming timing) {
        long startMillis = timing.startedAt() / 1_000_000;
        long firstChunkMillis = timing.firstChunkAt().get() / 1_000_000;
        long endMillis = timing.endAt().get() / 1_000_000;
        System.out.println("【并发验收】" + label + "：start=" + startMillis
                + ", firstChunk=" + firstChunkMillis + ", end=" + endMillis
                + "，首片段延迟 " + (firstChunkMillis - startMillis) + " ms");
    }
}
