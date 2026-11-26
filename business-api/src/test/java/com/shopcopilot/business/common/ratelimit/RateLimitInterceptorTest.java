package com.shopcopilot.business.common.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

import com.shopcopilot.business.config.RateLimitProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    @Mock
    private RedisRateLimiter rateLimiter;

    @Test
    @DisplayName("未配置受信任代理时忽略 X-Forwarded-For，直接使用 remoteAddr")
    void should_ignoreForwardedFor_when_noTrustedProxies() {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(
            rateLimiter, new RateLimitProperties(true, List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.7");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        assertEquals("198.51.100.7", interceptor.resolveClientIp(request));
    }

    @Test
    @DisplayName("直连来源不在受信任代理白名单内时忽略 X-Forwarded-For")
    void should_ignoreForwardedFor_when_remoteAddrNotTrusted() {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(
            rateLimiter, new RateLimitProperties(true, List.of("10.0.0.1")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.7");
        request.addHeader("X-Forwarded-For", "203.0.113.5");

        assertEquals("198.51.100.7", interceptor.resolveClientIp(request));
    }

    @Test
    @DisplayName("请求经受信任代理链转发时从右向左取最右侧非代理 IP，抵御左侧伪造")
    void should_pickRightmostUntrustedIp_when_behindTrustedProxies() {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(
            rateLimiter, new RateLimitProperties(true, List.of("10.0.0.1", "10.0.0.2")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.5, 10.0.0.2");

        assertEquals("203.0.113.5", interceptor.resolveClientIp(request));
    }

    @Test
    @DisplayName("限流关闭时直接放行不调用 Redis")
    void should_bypass_when_disabled() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(
            rateLimiter, new RateLimitProperties(false, List.of()));
        HandlerMethod handler = new HandlerMethod(new DummyController(), DummyController.class.getMethod("ok"));

        assertTrue(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler));
        verifyNoInteractions(rateLimiter);
    }

    static class DummyController {
        @RateLimit(maxRequests = 1, windowSeconds = 60)
        public void ok() {
        }
    }
}
