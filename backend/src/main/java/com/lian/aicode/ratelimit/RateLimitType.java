package com.lian.aicode.ratelimit;

/**
 * 限流维度。
 *
 * <p>用户维度按登录账号隔离配额；IP 维度用于不依赖登录态的保护场景。
 * 用户维度在无法解析登录态时直接放行（鉴权语义交给接口自身处理），不静默降级为 IP 配额。</p>
 */
public enum RateLimitType {

    /** 接口级别：整个接口共享一个配额。 */
    API,

    /** 用户级别：按登录用户隔离配额。 */
    USER,

    /** IP 级别：按客户端 IP 隔离配额。 */
    IP
}
