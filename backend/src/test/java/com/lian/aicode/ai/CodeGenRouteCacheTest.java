package com.lian.aicode.ai;

import com.lian.aicode.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 路由结果缓存的离线单测：命中、回填与 Redis 故障降级三个分支。 */
class CodeGenRouteCacheTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private CodeGenRouteCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cache = new CodeGenRouteCache(redisTemplate, true, Duration.ofHours(6), "test:route");
    }

    @Test
    void cacheMissReturnsNull() {
        when(valueOperations.get(anyString())).thenReturn(null);
        assertNull(cache.get("做一个官网"));
        verify(valueOperations).get(contains(":"));
    }

    @Test
    void cachedValueIsConvertedBackToEnum() {
        when(valueOperations.get(anyString())).thenReturn("vue_project");
        assertEquals(CodeGenTypeEnum.VUE_PROJECT, cache.get("做一个管理系统"));
    }

    @Test
    void putWritesValueWithTtl() {
        cache.put("做一个官网", CodeGenTypeEnum.HTML);
        verify(valueOperations).set(anyString(), eq("html"), eq(Duration.ofHours(6)));
    }

    @Test
    void redisFailureDegradesToNullWithoutThrowing() {
        when(valueOperations.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        assertNull(cache.get("做一个官网"));
    }

    @Test
    void disabledCacheNeverTouchesRedis() {
        CodeGenRouteCache disabled = new CodeGenRouteCache(redisTemplate, false,
                Duration.ofHours(6), "test:route");
        assertNull(disabled.get("做一个官网"));
        disabled.put("做一个官网", CodeGenTypeEnum.HTML);
        Mockito.verifyNoInteractions(valueOperations);
    }
}
