package com.lian.aicode.ratelimit;

import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import com.lian.aicode.model.entity.UserAccount;
import com.lian.aicode.service.UserService;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RRateLimiter;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 限流切面的离线单测：Redisson 以桩替代，覆盖放行、拒绝、降级和鉴权优先四个分支。 */
class RateLimitAspectTest {

    private RedissonRateLimiterProvider provider;
    private UserService userService;
    private Environment environment;
    private RateLimitAspect aspect;
    private RRateLimiter limiter;

    @BeforeEach
    void setUp() {
        provider = mock(RedissonRateLimiterProvider.class);
        userService = mock(UserService.class);
        environment = mock(Environment.class);
        when(provider.isEnabled()).thenReturn(true);
        // 未配置覆盖项时一律回退注解默认值。
        when(environment.getProperty(anyString(), Mockito.eq(Integer.class), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        aspect = new RateLimitAspect(provider, userService, environment);
        limiter = mock(RRateLimiter.class);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void bindRequest(MockHttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private JoinPoint joinPoint() throws Exception {
        Method method = RateLimitAspectTest.class.getDeclaredMethod("annotatedDummy");
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        JoinPoint joinPoint = mock(JoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        return joinPoint;
    }

    @RateLimit(key = "chat-gen-code", limitType = RateLimitType.USER, rate = 5, rateInterval = 60)
    @SuppressWarnings("unused")
    private void annotatedDummy() {
    }

    @Test
    void acquireSuccessPassesAndSetsRate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        bindRequest(request);
        when(userService.getLoginUser(request)).thenReturn(user(1L));
        when(provider.getRateLimiter(anyString()))
                .thenReturn(new RedissonRateLimiterProvider.RRateLimiterHolder(limiter));
        when(limiter.tryAcquire(1)).thenReturn(true);

        assertDoesNotThrow(() -> aspect.doBefore(joinPoint(), annotation()));
        // 顺序守护：trySetRate 创建配置 → tryAcquire 判定 → expire 补 TTL。
        // expire 必须在判定之后——首次请求时键才存在，先 expire 会落空并留下永不过期的键。
        org.mockito.InOrder inOrder = Mockito.inOrder(limiter);
        inOrder.verify(limiter).trySetRate(any(), anyLong(), anyLong(), any());
        inOrder.verify(limiter).tryAcquire(1);
        inOrder.verify(limiter).expire(org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    void acquireRejectedThrowsTooManyRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        bindRequest(request);
        when(userService.getLoginUser(request)).thenReturn(user(1L));
        when(provider.getRateLimiter(anyString()))
                .thenReturn(new RedissonRateLimiterProvider.RRateLimiterHolder(limiter));
        when(limiter.tryAcquire(1)).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> aspect.doBefore(joinPoint(), annotation()));
        assertEquals(ErrorCode.TOO_MANY_REQUEST.getCode(), exception.getCode());
    }

    @Test
    void unauthenticatedUserPassesWithoutConsumingQuota() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        bindRequest(request);
        when(userService.getLoginUser(request)).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        assertDoesNotThrow(() -> aspect.doBefore(joinPoint(), annotation()));
        // 鉴权优先：未登录请求不触达 Redisson，也不消耗 IP 配额。
        verify(provider, never()).getRateLimiter(anyString());
    }

    @Test
    void degradedProviderPassesOpen() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        bindRequest(request);
        when(userService.getLoginUser(request)).thenReturn(user(1L));
        when(provider.getRateLimiter(anyString())).thenReturn(null);

        assertDoesNotThrow(() -> aspect.doBefore(joinPoint(), annotation()));
        verify(limiter, never()).tryAcquire(anyInt());
    }

    @Test
    void redisRuntimeFailurePassesOpen() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        bindRequest(request);
        when(userService.getLoginUser(request)).thenReturn(user(1L));
        when(provider.getRateLimiter(anyString()))
                .thenReturn(new RedissonRateLimiterProvider.RRateLimiterHolder(limiter));
        when(limiter.tryAcquire(1)).thenThrow(new IllegalStateException("redis down"));

        assertDoesNotThrow(() -> aspect.doBefore(joinPoint(), annotation()));
    }

    @Test
    void ipTypeLimitsByClientIp() throws Exception {
        Method method = RateLimitAspectTest.class.getDeclaredMethod("ipDummy");
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        JoinPoint joinPoint = mock(JoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        bindRequest(request);
        when(provider.getRateLimiter(anyString()))
                .thenReturn(new RedissonRateLimiterProvider.RRateLimiterHolder(limiter));
        when(limiter.tryAcquire(1)).thenReturn(true);

        assertDoesNotThrow(() -> aspect.doBefore(joinPoint, ipAnnotation()));
        verify(provider).getRateLimiter(org.mockito.ArgumentMatchers.eq("rate_limit:guard:ip:203.0.113.7"));
    }

    @Test
    void configuredOverrideReplacesAnnotationDefaults() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        bindRequest(request);
        when(userService.getLoginUser(request)).thenReturn(user(1L));
        when(provider.getRateLimiter(anyString()))
                .thenReturn(new RedissonRateLimiterProvider.RRateLimiterHolder(limiter));
        when(limiter.tryAcquire(1)).thenReturn(true);
        // 配置覆盖：app.rate-limit.chat-gen-code.rate/interval-seconds 优先于注解默认值。
        when(environment.getProperty("app.rate-limit.chat-gen-code.rate", Integer.class, 5)).thenReturn(7);
        when(environment.getProperty("app.rate-limit.chat-gen-code.interval-seconds", Integer.class, 60))
                .thenReturn(90);

        assertDoesNotThrow(() -> aspect.doBefore(joinPoint(), annotation()));
        verify(limiter).trySetRate(any(), org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(90L), any());
    }

    @Test
    void disabledSwitchShortCircuitsBeforeLoginResolution() throws Exception {
        when(provider.isEnabled()).thenReturn(false);

        assertDoesNotThrow(() -> aspect.doBefore(joinPoint(), annotation()));
        // 整体短路：禁用时不解析登录态，也不触达 Redisson。
        verify(userService, never()).getLoginUser(any());
        verify(provider, never()).getRateLimiter(anyString());
    }

    @RateLimit(key = "guard", limitType = RateLimitType.IP)
    @SuppressWarnings("unused")
    private void ipDummy() {
    }

    private RateLimit annotation() throws Exception {
        return RateLimitAspectTest.class.getDeclaredMethod("annotatedDummy").getAnnotation(RateLimit.class);
    }

    private RateLimit ipAnnotation() throws Exception {
        return RateLimitAspectTest.class.getDeclaredMethod("ipDummy").getAnnotation(RateLimit.class);
    }

    private UserAccount user(Long id) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setUserAccount("tester");
        return account;
    }
}
