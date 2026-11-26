package com.shopcopilot.business.common.ratelimit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class RedisRateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private RedisRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new RedisRateLimiter(redisTemplate);
    }

    @Test
    @DisplayName("滑动窗口脚本返回正数放行，返回 0 拒绝")
    void should_allowWhenScriptPositive_andRejectWhenZero() {
        when(redisTemplate.execute(any(), eq(List.of("k")), eq("60000"), eq("5"), anyString()))
            .thenReturn(1L, 5L, 0L);

        assertTrue(rateLimiter.tryAcquire("k", 5, 60));
        assertTrue(rateLimiter.tryAcquire("k", 5, 60));
        assertFalse(rateLimiter.tryAcquire("k", 5, 60));
    }

    @Test
    @DisplayName("Redis 异常或返回 null 时降级放行")
    void should_failOpen_when_redisErrorsOrReturnsNull() {
        when(redisTemplate.execute(any(), eq(List.of("k")), eq("10000"), eq("5"), anyString()))
            .thenReturn(null)
            .thenThrow(new QueryTimeoutException("timeout"));

        assertTrue(rateLimiter.tryAcquire("k", 5, 10));
        assertTrue(rateLimiter.tryAcquire("k", 5, 10));
    }

    @Test
    @DisplayName("非法限流参数抛出 IllegalArgumentException")
    void should_throw_when_parametersInvalid() {
        assertThrows(IllegalArgumentException.class, () -> rateLimiter.tryAcquire("k", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> rateLimiter.tryAcquire("k", 5, 0));
    }
}
