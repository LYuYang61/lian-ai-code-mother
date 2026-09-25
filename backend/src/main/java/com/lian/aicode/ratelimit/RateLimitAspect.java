package com.lian.aicode.ratelimit;

import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import com.lian.aicode.model.entity.UserAccount;
import com.lian.aicode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * 限流切面：在标注 {@link RateLimit} 的方法执行前按 Redisson 令牌桶判定配额。
 *
 * <p>设计要点（按本期确认的方案）：</p>
 * <ul>
 *   <li>鉴权优先于限流：用户维度先解析登录态，未登录请求直接放行，
 *       让接口自身的“未登录”语义优先，避免匿名流量消耗用户配额或混淆错误码；</li>
 *   <li>失败降级：Redis 未启用或不可用时放行（fail-open），完整告警一次“限流已降级”；</li>
 *   <li>key 在每次判定后设置过期时间（首次请求时键才由 trySetRate 创建），防止长时间运行后
 *       Redis 内存被限流 key 无限占用；</li>
 *   <li>限流参数可用 {@code app.rate-limit.<key>.*} 配置覆盖注解默认值。</li>
 * </ul>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private static final String KEY_PREFIX = "rate_limit";

    private final RedissonRateLimiterProvider limiterProvider;
    private final UserService userService;
    private final Environment environment;

    @Before("@annotation(rateLimit)")
    public void doBefore(JoinPoint joinPoint, RateLimit rateLimit) {
        // 开关关闭时整体短路：连登录态解析（一次数据库用户加载）都不执行。
        if (!limiterProvider.isEnabled()) {
            return;
        }
        String key = generateRateLimitKey(joinPoint, rateLimit);
        if (key == null) {
            // 未登录（用户维度）或无请求上下文：放行，鉴权由接口自身完成。
            return;
        }
        RedissonRateLimiterProvider.RRateLimiterHolder holder = limiterProvider.getRateLimiter(key);
        if (holder == null) {
            // 未启用或降级中：放行（RedissonRateLimiterProvider 已记录降级告警）。
            return;
        }
        int rate = resolveConfiguredValue(rateLimit.key(), "rate", rateLimit.rate());
        int intervalSeconds = resolveConfiguredValue(rateLimit.key(), "interval-seconds", rateLimit.rateInterval());
        try {
            RRateLimiter limiter = holder.limiter();
            limiter.trySetRate(RateType.OVERALL, rate, intervalSeconds, RateIntervalUnit.SECONDS);
            boolean acquired = limiter.tryAcquire(1);
            // TTL 必须在判定之后统一补：首次请求时键由 trySetRate 创建，先 expire 会因键不存在而落空，
            // 只发起过一次请求的维度将留下永不过期的常驻键（教程同款顺序存在此边界）。
            limiter.expire(Duration.ofHours(1));
            if (!acquired) {
                log.warn("接口限流触发：key={}, rate={}/{}s, result=拒绝", key, rate, intervalSeconds);
                throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, rateLimit.message());
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Redis 运行期异常不能拖垮业务请求：放行并记录降级日志。
            log.warn("限流判定异常，已降级放行：key={}, reason={}", key, exception.getClass().getSimpleName());
        }
    }

    /**
     * 生成限流 key；返回 null 表示本次不限流。
     *
     * <p>用户维度取不到登录态时放行（鉴权优先）；IP 维度只依赖请求头，总能生成 key。</p>
     */
    private String generateRateLimitKey(JoinPoint joinPoint, RateLimit rateLimit) {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            log.debug("限流跳过：无请求上下文，method={}", describeMethod(joinPoint));
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        StringBuilder keyBuilder = new StringBuilder(KEY_PREFIX).append(':');
        if (!rateLimit.key().isEmpty()) {
            keyBuilder.append(rateLimit.key()).append(':');
        }
        switch (rateLimit.limitType()) {
            case API -> keyBuilder.append("api:").append(describeMethod(joinPoint));
            case USER -> {
                UserAccount loginUser;
                try {
                    loginUser = userService.getLoginUser(request);
                } catch (BusinessException exception) {
                    // 未登录：放行，交给接口自身返回明确的“未登录”业务码。
                    log.debug("限流跳过：用户维度未登录，method={}", describeMethod(joinPoint));
                    return null;
                }
                keyBuilder.append("user:").append(loginUser.getId());
            }
            case IP -> keyBuilder.append("ip:").append(getClientIP(request));
            default -> {
                log.warn("限流维度无效：limitType={}, method={}", rateLimit.limitType(),
                        describeMethod(joinPoint));
                return null;
            }
        }
        return keyBuilder.toString();
    }

    /** 优先读取 app.rate-limit.<key>.<name> 配置，未配置时回退注解默认值。 */
    private int resolveConfiguredValue(String key, String name, int defaultValue) {
        if (key == null || key.isBlank()) {
            return defaultValue;
        }
        return environment.getProperty("app.rate-limit." + key + "." + name, Integer.class, defaultValue);
    }

    private String describeMethod(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }

    /** 解析客户端 IP：优先代理头，其次远端地址；多级代理取第一个。 */
    private String getClientIP(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip == null || ip.isEmpty() ? "unknown" : ip;
    }
}
