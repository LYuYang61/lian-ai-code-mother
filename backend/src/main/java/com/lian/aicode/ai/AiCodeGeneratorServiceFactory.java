package com.lian.aicode.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lian.aicode.ai.guardrail.PromptSafetyInputGuardrail;
import com.lian.aicode.ai.tools.ProjectToolBundle;
import com.lian.aicode.service.ChatHistoryService;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * AI Service 创建和应用级会话隔离适配层。
 *
 * <p>应用名称和摘要使用无状态默认服务；代码生成使用按 appId 缓存的独立服务实例。
 * 每个实例绑定独立的 ChatMemory，避免不同应用之间共享上下文，也保留 Caffeine 的容量和过期
 * 边界，防止长时间运行的后端进程无限持有对象。</p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = {
        "langchain4j.open-ai.chat-model.api-key",
        "langchain4j.open-ai.streaming-chat-model.api-key"
})
public class AiCodeGeneratorServiceFactory {

    private static final int MAX_MEMORY_MESSAGES = 200;

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final RedisChatMemoryStore redisChatMemoryStore;
    private final ChatHistoryService chatHistoryService;
    private final boolean chatMemoryEnabled;
    private final int maxMessages;
    private final long serviceCacheMaxSize;
    private final Duration expireAfterWrite;
    private final Duration expireAfterAccess;
    private final Cache<Long, AiCodeGeneratorService> appServiceCache;

    public AiCodeGeneratorServiceFactory(@Qualifier("openAiChatModel") ChatModel chatModel,
                                         StreamingChatModel streamingChatModel,
                                         RedisChatMemoryStore redisChatMemoryStore,
                                         ChatHistoryService chatHistoryService,
                                         @Value("${app.ai.chat-memory.enabled:true}") boolean chatMemoryEnabled,
                                         @Value("${app.ai.chat-memory.max-messages:20}") int maxMessages,
                                         @Value("${app.ai.chat-memory.service-cache-max-size:1000}") long serviceCacheMaxSize,
                                         @Value("${app.ai.chat-memory.service-cache-expire-after-write:30m}") Duration expireAfterWrite,
                                         @Value("${app.ai.chat-memory.service-cache-expire-after-access:10m}") Duration expireAfterAccess) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.redisChatMemoryStore = redisChatMemoryStore;
        this.chatHistoryService = chatHistoryService;
        this.chatMemoryEnabled = chatMemoryEnabled;
        this.maxMessages = Math.min(Math.max(maxMessages, 2), MAX_MEMORY_MESSAGES);
        this.serviceCacheMaxSize = Math.max(serviceCacheMaxSize, 1);
        this.expireAfterWrite = expireAfterWrite.isNegative() || expireAfterWrite.isZero()
                ? Duration.ofMinutes(30) : expireAfterWrite;
        this.expireAfterAccess = expireAfterAccess.isNegative() || expireAfterAccess.isZero()
                ? Duration.ofMinutes(10) : expireAfterAccess;
        this.appServiceCache = Caffeine.newBuilder()
                .maximumSize(this.serviceCacheMaxSize)
                .expireAfterWrite(this.expireAfterWrite)
                .expireAfterAccess(this.expireAfterAccess)
                .removalListener((Long appId, AiCodeGeneratorService service, com.github.benmanes.caffeine.cache.RemovalCause cause) ->
                        log.info("移除应用 AI 服务缓存：appId={}, reason={}", appId, cause))
                .build();
        log.info("初始化 AI 服务工厂：chatMemoryEnabled={}, maxMessages={}, cacheMaxSize={}",
                chatMemoryEnabled, this.maxMessages, this.serviceCacheMaxSize);
    }

    /**
     * 默认无状态服务只负责应用命名和摘要等跨应用操作，不访问 Redis。
     * 这样只配置 API Key、但尚未启动 Redis 时，基础应用仍可启动。
     */
    @Bean
    public AiCodeGeneratorService aiCodeGeneratorService() {
        return buildService(null);
    }

    /** 获取指定应用的独立 AI Service。 */
    public AiCodeGeneratorService getForApp(Long appId) {
        return getForApp(appId, null);
    }

    /**
     * 获取指定应用的独立 AI Service；首次装载数据库历史时排除本次已经落库、即将由 LangChain4j
     * 自动加入记忆的用户消息，避免当前消息重复进入上下文。
     */
    public AiCodeGeneratorService getForApp(Long appId, Long excludedMessageId) {
        if (appId == null || appId <= 0) {
            throw new IllegalArgumentException("appId 必须为正数");
        }
        return appServiceCache.get(appId, id -> createAppService(id, excludedMessageId));
    }

    /**
     * 创建一次性的 Vue 工程 AI Service。
     *
     * <p>工具绑定的是具体版本目录，不能放进按 appId 缓存的服务，否则同一个应用的两个
     * 版本在并发或取消后可能互相写文件。因此 HTML/MULTI 使用缓存，Vue 工程使用任务级实例。</p>
     */
    public AiCodeGeneratorService getForVueProject(Long appId, Long excludedMessageId,
                                                    ProjectToolBundle toolBundle) {
        if (appId == null || appId <= 0 || toolBundle == null) {
            throw new IllegalArgumentException("Vue 工程 AI Service 参数无效");
        }
        MessageWindowChatMemory memory = createChatMemory(appId, excludedMessageId);
        AiCodeGeneratorService service = AiServices.builder(AiCodeGeneratorService.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(memoryId -> memory)
                .tools(toolBundle.tools())
                // 模型偶尔会返回不存在的工具名；把错误交回模型，而不是让请求静默成功。
                .hallucinatedToolNameStrategy(request -> ToolExecutionResultMessage.from(
                        request, "不存在名为 " + request.name() + " 的工具，请改用已声明的文件工具"))
                // 2026-09-23 实测：关闭思考后模型会认真执行多轮写文件并反复复读校验，
                // 电商管理后台类需求 20 轮（12 次写入 + 多轮校验读）恰好耗尽上限导致生成失败；放宽到 40。
                .maxSequentialToolsInvocations(40)
                .inputGuardrails(new PromptSafetyInputGuardrail())
                .build();
        log.info("创建 Vue 工程 AI Service：appId={}, excludedMessageId={}, toolCount={}",
                appId, excludedMessageId, toolBundle.tools().size());
        return service;
    }

    /** 删除应用时同步清理本地缓存和 Redis 中的模型记忆。 */
    public void evictAppService(Long appId) {
        if (appId == null || appId <= 0) {
            return;
        }
        appServiceCache.invalidate(appId);
        if (chatMemoryEnabled) {
            redisChatMemoryStore.deleteMessages(memoryId(appId));
        }
        log.info("清理应用 AI 对话记忆：appId={}", appId);
    }

    private AiCodeGeneratorService createAppService(Long appId, Long excludedMessageId) {
        AiCodeGeneratorService service = buildService(appId, excludedMessageId);
        log.info("创建应用级 AI Service：appId={}, chatMemoryEnabled={}", appId, chatMemoryEnabled);
        return service;
    }

    private AiCodeGeneratorService buildService(Long appId) {
        return buildService(appId, null);
    }

    private AiCodeGeneratorService buildService(Long appId, Long excludedMessageId) {
        var builder = AiServices.builder(AiCodeGeneratorService.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel);
        if (appId == null) {
            // 接口包含带 @MemoryId 的 Vue 方法；即使默认命名/摘要服务暂时不用记忆，
            // LangChain4j 仍要求在构建代理时声明 ChatMemoryProvider。
            return builder.chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                    .id(memoryId)
                    .maxMessages(maxMessages)
                    .build()).build();
        }
        var chatMemory = createChatMemory(appId, excludedMessageId);
        return builder.chatMemoryProvider(memoryId -> chatMemory).build();
    }

    private MessageWindowChatMemory createChatMemory(Long appId, Long excludedMessageId) {
        MessageWindowChatMemory.Builder memoryBuilder = MessageWindowChatMemory.builder()
                .id(memoryId(appId))
                .maxMessages(maxMessages)
                .alwaysKeepSystemMessageFirst(true);
        if (chatMemoryEnabled) {
            memoryBuilder.chatMemoryStore(redisChatMemoryStore);
        }
        MessageWindowChatMemory chatMemory = memoryBuilder.build();
        // 首次创建时从数据库恢复；后续请求由 LangChain4j 自动更新 Redis 窗口。
        chatHistoryService.loadChatHistoryToMemory(appId, chatMemory, maxMessages, excludedMessageId);
        return chatMemory;
    }

    private String memoryId(Long appId) {
        return "app:" + appId;
    }
}
