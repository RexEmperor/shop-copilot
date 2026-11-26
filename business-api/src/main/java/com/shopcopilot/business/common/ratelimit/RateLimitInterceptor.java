package com.shopcopilot.business.common.ratelimit;

import com.shopcopilot.business.common.BusinessException;
import com.shopcopilot.business.config.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 基于 {@link RateLimit} 注解的 MVC 请求限流拦截器。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final String KEY_NAMESPACE = "shop:ratelimit:";

    private final RedisRateLimiter rateLimiter;
    private final RateLimitProperties properties;

    public RateLimitInterceptor(RedisRateLimiter rateLimiter, RateLimitProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.enabled() || !(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RateLimit rule = resolveRule(handlerMethod);
        if (rule == null) {
            return true;
        }
        String key = buildKey(request, handlerMethod, rule);
        if (!rateLimiter.tryAcquire(key, rule.maxRequests(), rule.windowSeconds())) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
        }
        return true;
    }

    private RateLimit resolveRule(HandlerMethod handlerMethod) {
        RateLimit methodRule = AnnotatedElementUtils.findMergedAnnotation(
            handlerMethod.getMethod(), RateLimit.class);
        if (methodRule != null) {
            return methodRule;
        }
        return AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RateLimit.class);
    }

    private String buildKey(HttpServletRequest request, HandlerMethod handlerMethod, RateLimit rule) {
        String prefix = StringUtils.hasText(rule.keyPrefix())
            ? rule.keyPrefix().trim()
            : handlerMethod.getBeanType().getSimpleName() + "#" + handlerMethod.getMethod().getName();
        return KEY_NAMESPACE + prefix + ":" + resolveClientIp(request);
    }

    /**
     * 解析客户端 IP：
     * <ul>
     *   <li>仅当直连对端 {@code remoteAddr} 位于受信任代理白名单内时，才读取 {@code X-Forwarded-For}；</li>
     *   <li>解析 {@code X-Forwarded-For} 时从右向左跳过白名单代理，取最右侧非代理 IP，防止客户端伪造头部左侧内容绕过限流；</li>
     *   <li>未配置受信任代理或非代理直连时，完全忽略 {@code X-Forwarded-For}，直接使用 {@code remoteAddr}。</li>
     * </ul>
     */
    String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = StringUtils.hasText(request.getRemoteAddr())
            ? request.getRemoteAddr().trim()
            : "unknown";
        if (!properties.isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (!StringUtils.hasText(forwardedFor)) {
            return remoteAddr;
        }
        Set<String> trusted = properties.trustedProxySet();
        String[] parts = forwardedFor.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String candidate = parts[i].trim();
            if (StringUtils.hasText(candidate) && !trusted.contains(candidate)) {
                return candidate;
            }
        }
        return remoteAddr;
    }
}
