package com.lian.aicode.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redisson 客户端的懒初始化提供器（第十期限流的降级开关实现）。
 *
 * <p>本项目将限流定位为可选增强能力，遵循“单项可选、失败降级”的既有风格：
 * 应用启动不依赖 Redis；第一次限流判定时才创建 Redisson 客户端，创建失败时输出
 * “限流已降级”告警并放行业务（fail-open），之后按固定间隔静默重试，Redis 恢复后限流自动生效。</p>
 */
@Slf4j
@Component
public class RedissonRateLimiterProvider implements DisposableBean {

    /** 降级后重试建立连接的间隔；期间所有请求直接放行，避免每个请求都等待连接超时。 */
    static final long RETRY_INTERVAL_MILLIS = 60_000L;

    private final boolean enabled;
    private final String address;
    private final String username;
    private final String password;
    private final int database;

    private volatile RedissonClient client;
    private final AtomicLong lastFailureAt = new AtomicLong(0);
    private final AtomicBoolean degradedLogged = new AtomicBoolean(false);

    public RedissonRateLimiterProvider(
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.username:}") String username,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.database:0}") int database) {
        this.enabled = enabled;
        this.address = "redis://" + host + ":" + port;
        this.username = username;
        this.password = password;
        this.database = database;
        if (enabled) {
            log.info("限流功能已启用（Redisson 客户端懒初始化）：address={}", address);
        } else {
            log.info("限流功能已通过配置关闭：app.rate-limit.enabled=false");
        }
    }

    /** 限流是否启用（配置开关）。 */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 获取 key 对应的分布式限流器；未启用、降级中或运行期异常时返回 null，由切面放行。
     *
     * @param key 完整的限流 key（已包含维度与业务前缀）
     */
    public RRateLimiterHolder getRateLimiter(String key) {
        RedissonClient current = ensureClient();
        if (current == null) {
            return null;
        }
        try {
            return new RRateLimiterHolder(current.getRateLimiter(key));
        } catch (RuntimeException exception) {
            logDegraded("获取限流器失败", exception);
            return null;
        }
    }

    /** 惰性创建 Redisson 客户端；失败后按间隔重试，成功后复用。 */
    private synchronized RedissonClient ensureClient() {
        if (!enabled) {
            return null;
        }
        if (client != null) {
            return client;
        }
        long now = System.currentTimeMillis();
        long lastFailure = lastFailureAt.get();
        if (lastFailure > 0 && now - lastFailure < RETRY_INTERVAL_MILLIS) {
            return null;
        }
        try {
            Config config = new Config();
            SingleServerConfig serverConfig = config.useSingleServer()
                    .setAddress(address)
                    .setDatabase(database)
                    .setConnectionMinimumIdleSize(1)
                    .setConnectionPoolSize(10)
                    .setIdleConnectionTimeout(30_000)
                    .setConnectTimeout(5_000)
                    .setTimeout(3_000)
                    .setRetryAttempts(2)
                    .setRetryInterval(1_500);
            if (username != null && !username.isBlank()) {
                serverConfig.setUsername(username);
            }
            if (password != null && !password.isBlank()) {
                serverConfig.setPassword(password);
            }
            client = Redisson.create(config);
            degradedLogged.set(false);
            lastFailureAt.set(0);
            log.info("Redisson 限流客户端初始化成功：address={}, database={}", address, database);
            return client;
        } catch (RuntimeException exception) {
            lastFailureAt.set(System.currentTimeMillis());
            logDegraded("Redisson 客户端初始化失败，限流已降级放行", exception);
            return null;
        }
    }

    /** 降级完整告警一次；恢复成功后重置标记，再次降级仍会告警。 */
    private void logDegraded(String scene, RuntimeException exception) {
        if (degradedLogged.compareAndSet(false, true)) {
            log.warn("{}：address={}, reason={}, result=限流降级放行", scene, address,
                    exception.getClass().getSimpleName(), exception);
        } else {
            log.debug("{}：reason={}", scene, exception.getClass().getSimpleName());
        }
    }

    @Override
    public void destroy() {
        if (client != null) {
            client.shutdown();
            log.info("Redisson 限流客户端已释放");
        }
    }

    /** 包装 RRateLimiter，避免切面直接依赖 Redisson 类型做判空，也便于单测替换。 */
    public record RRateLimiterHolder(org.redisson.api.RRateLimiter limiter) {
    }
}
