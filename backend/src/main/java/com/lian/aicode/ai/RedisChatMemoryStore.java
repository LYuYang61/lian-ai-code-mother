package com.lian.aicode.ai;

import com.lian.aicode.service.ChatHistoryService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 基于 Spring Data Redis 的 LangChain4j ChatMemoryStore。
 *
 * <p>不直接依赖教程中的旧 community Redis starter，而是使用 LangChain4j 官方支持的
 * {@link ChatMemoryStore} 扩展点和 Spring Boot 3.5 管理的 Redis 客户端，减少版本耦合。</p>
 */
@Slf4j
@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final int MAX_MEMORY_MESSAGES = 200;

    private final StringRedisTemplate redisTemplate;
    private final ObjectProvider<ChatHistoryService> chatHistoryServiceProvider;
    private final String keyPrefix;
    private final Duration ttl;
    private final int maxMessages;

    public RedisChatMemoryStore(StringRedisTemplate redisTemplate,
                                ObjectProvider<ChatHistoryService> chatHistoryServiceProvider,
                                @Value("${app.ai.chat-memory.key-prefix:lian:ai:chat-memory}") String keyPrefix,
                                @Value("${app.ai.chat-memory.ttl:86400s}") Duration ttl,
                                @Value("${app.ai.chat-memory.max-messages:20}") int maxMessages) {
        this.redisTemplate = redisTemplate;
        this.chatHistoryServiceProvider = chatHistoryServiceProvider;
        this.keyPrefix = keyPrefix;
        this.ttl = ttl;
        this.maxMessages = Math.min(Math.max(maxMessages, 1), MAX_MEMORY_MESSAGES);
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = key(memoryId);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return restoreFromDatabase(memoryId);
            }
            redisTemplate.expire(key, ttl);
            return ChatMessageDeserializer.messagesFromJson(json);
        } catch (RuntimeException exception) {
            log.warn("读取 Redis 对话记忆失败，尝试从数据库恢复：memoryId={}", memoryId, exception);
            return restoreFromDatabase(memoryId);
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = key(memoryId);
        try {
            redisTemplate.opsForValue().set(key, ChatMessageSerializer.messagesToJson(messages), ttl);
            log.debug("更新 Redis 对话记忆：memoryId={}, messageCount={}", memoryId, messages.size());
        } catch (RuntimeException exception) {
            // 数据库是完整历史的事实来源；Redis 写入失败不应让已经完成的模型响应变成失败。
            log.warn("写入 Redis 对话记忆失败，将由数据库历史兜底：memoryId={}, messageCount={}",
                    memoryId, messages.size(), exception);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        try {
            redisTemplate.delete(key(memoryId));
            log.debug("删除 Redis 对话记忆：memoryId={}", memoryId);
        } catch (RuntimeException exception) {
            log.warn("删除 Redis 对话记忆失败：memoryId={}", memoryId, exception);
        }
    }

    private List<ChatMessage> restoreFromDatabase(Object memoryId) {
        ChatHistoryService historyService = chatHistoryServiceProvider.getIfAvailable();
        if (historyService == null) {
            return List.of();
        }
        try {
            List<ChatMessage> messages = historyService.loadMemoryMessages(memoryId.toString(), maxMessages);
            if (!messages.isEmpty()) {
                try {
                    redisTemplate.opsForValue().set(key(memoryId), ChatMessageSerializer.messagesToJson(messages), ttl);
                } catch (RuntimeException cacheException) {
                    log.warn("回填 Redis 对话记忆失败，但已返回数据库消息：memoryId={}", memoryId, cacheException);
                }
            }
            log.info("Redis 对话记忆缺失，已从数据库恢复：memoryId={}, messageCount={}", memoryId, messages.size());
            return messages;
        } catch (RuntimeException exception) {
            log.error("从数据库恢复 Redis 对话记忆失败：memoryId={}", memoryId, exception);
            return List.of();
        }
    }

    private String key(Object memoryId) {
        if (memoryId == null || memoryId.toString().isBlank()) {
            throw new IllegalArgumentException("memoryId 不能为空");
        }
        return keyPrefix + ":" + memoryId;
    }
}
