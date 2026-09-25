package com.lian.aicode.ai;

import com.lian.aicode.model.enums.CodeGenTypeEnum;
import com.lian.aicode.utils.CacheKeyUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 智能路由结果缓存（教程 11 期成本优化的“AI 响应缓存”扩展）。
 *
 * <p>相同需求描述的路由结论在 TTL 内直接复用，减少重复的模型调用。缓存值只存
 * {@link CodeGenTypeEnum} 的 value 字符串，用 {@link StringRedisTemplate} 直接读写，
 * 与 {@code RedisChatMemoryStore} 是同一种轻量接入方式，避免嵌套泛型反序列化问题。
 * Redis 不可用时按项目惯例单项降级：读未命中、写静默放弃，路由回退到真实模型调用。</p>
 */
@Slf4j
@Component
public class CodeGenRouteCache {

    private final StringRedisTemplate redisTemplate;
    private final boolean enabled;
    private final Duration ttl;
    private final String keyPrefix;
    private final AtomicBoolean degradedLogged = new AtomicBoolean(false);

    public CodeGenRouteCache(StringRedisTemplate redisTemplate,
                             @Value("${app.cache.codegen-route.enabled:true}") boolean enabled,
                             @Value("${app.cache.codegen-route.ttl:6h}") Duration ttl,
                             @Value("${app.cache.codegen-route.key-prefix:lian:cache:codegen-route}") String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofHours(6) : ttl;
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "lian:cache:codegen-route" : keyPrefix;
        log.info("初始化路由结果缓存：enabled={}, ttl={}, keyPrefix={}", enabled, this.ttl, this.keyPrefix);
    }

    /** 读取缓存的路由结论；未命中、未启用或 Redis 异常时返回 null，由调用方继续走模型路由。 */
    public CodeGenTypeEnum get(String prompt) {
        if (!enabled || prompt == null || prompt.isBlank()) {
            return null;
        }
        try {
            String value = redisTemplate.opsForValue().get(key(prompt));
            CodeGenTypeEnum type = value == null ? null : CodeGenTypeEnum.getEnumByValue(value);
            if (type != null) {
                log.info("命中路由结果缓存：keyPrefix={}, result={}", keyPrefix, type.getValue());
            }
            return type;
        } catch (RuntimeException exception) {
            logDegraded(exception);
            return null;
        }
    }

    /** 写入路由结论；失败只记录告警，不影响路由结果本身。 */
    public void put(String prompt, CodeGenTypeEnum type) {
        if (!enabled || prompt == null || prompt.isBlank() || type == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key(prompt), type.getValue(), ttl);
        } catch (RuntimeException exception) {
            logDegraded(exception);
        }
    }

    private String key(String prompt) {
        return keyPrefix + ":" + CacheKeyUtils.generateKey(prompt);
    }

    /** 降级只完整告警一次，后续用 debug 级别避免刷屏。 */
    private void logDegraded(RuntimeException exception) {
        if (degradedLogged.compareAndSet(false, true)) {
            log.warn("路由结果缓存不可用，已降级为直接调用模型：reason={}",
                    exception.getClass().getSimpleName());
        } else {
            log.debug("路由结果缓存访问失败：reason={}", exception.getClass().getSimpleName());
        }
    }
}
