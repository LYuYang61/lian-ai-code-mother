package com.lian.aicode.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 基于 Redisson 令牌桶的分布式限流注解，标注在 Controller 方法上。
 *
 * <p>rate / rateInterval 是注解上的默认值；若配置了
 * {@code app.rate-limit.<key>.rate} / {@code app.rate-limit.<key>.interval-seconds}
 * （key 为本注解的 key 属性），切面优先使用配置值，便于不改代码调整限流参数。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 限流 key 前缀；同时用于派生配置项名 app.rate-limit.<key>.*。 */
    String key() default "";

    /** 每个时间窗口允许的请求数。 */
    int rate() default 10;

    /** 时间窗口（秒）。 */
    int rateInterval() default 1;

    /** 限流维度。 */
    RateLimitType limitType() default RateLimitType.USER;

    /** 限流触发后的用户可见提示。 */
    String message() default "请求过于频繁，请稍后再试";
}
