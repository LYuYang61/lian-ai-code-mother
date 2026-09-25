package com.lian.aicode.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Spring Cache 的 Redis 缓存管理器（教程 11 期“Redis 缓存优化”的旁路缓存实现）。
 *
 * <p>key 使用 String 序列化保证 Redis 中可读；value 使用带类型信息的 JSON 序列化，
 * 使 {@code BaseResponse<PageResult<AppVO>>} 这类嵌套泛型结果能够正确往返。
 * 精选应用列表更新频率低（管理员手工设置），使用较短的 5 分钟 TTL 兜底数据一致性，
 * 不做主动失效：审核/置顶后列表最长滞后一个 TTL，属于本项目接受的权衡。</p>
 *
 * <p>{@code app.cache.enabled=false}（含自动化测试环境）时改用 NoOp 缓存管理器：
 * 注解仍然生效但直连数据库，避免离线测试依赖 Redis。</p>
 */
@Configuration
public class RedisCacheManagerConfig {

    /** 精选应用分页列表缓存区域名；首页默认前 10 页命中率最高。 */
    public static final String GOOD_APP_PAGE_CACHE = "good_app_page";

    @Bean
    @ConditionalOnProperty(name = "app.cache.enabled", havingValue = "true", matchIfMissing = true)
    public CacheManager redisCacheManager(RedisConnectionFactory redisConnectionFactory,
                                          @Value("${app.cache.default-ttl:30m}") Duration defaultTtl,
                                          @Value("${app.cache.good-app-page-ttl:5m}") Duration goodAppPageTtl) {
        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(buildCacheObjectMapper());

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(safeTtl(defaultTtl, Duration.ofMinutes(30)))
                .disableCachingNullValues()
                .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(SerializationPair.fromSerializer(valueSerializer));

        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(defaultConfig)
                .withCacheConfiguration(GOOD_APP_PAGE_CACHE,
                        defaultConfig.entryTtl(safeTtl(goodAppPageTtl, Duration.ofMinutes(5))))
                .build();
    }

    /** 关闭缓存开关时（含离线测试环境）提供直连数据库的空缓存管理器。 */
    @Bean
    @ConditionalOnProperty(name = "app.cache.enabled", havingValue = "false")
    public CacheManager noOpCacheManager() {
        return new NoOpCacheManager();
    }

    /**
     * 构建缓存值序列化用的 ObjectMapper（包级可见供离线往返测试复用，保证测试与生产同构）。
     *
     * <p>项目内被缓存的时间字段都已字符串化；JavaTimeModule 是防御性配置，
     * 避免后续字段类型变化时出现 InvalidDefinitionException。{@code @class} 多态反序列化
     * 用包名前缀白名单约束（比 Jackson 默认的放行策略更严格），防止 Redis 中被注入的
     * 类型信息指向任意类。</p>
     */
    static ObjectMapper buildCacheObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.lian.aicode.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .allowIfSubType("java.lang.")
                .allowIfSubType("java.sql.")
                .build();
        objectMapper.activateDefaultTyping(typeValidator,
                ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        return objectMapper;
    }

    private Duration safeTtl(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
