package com.shopcopilot.business.config;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 接口限流配置。
 *
 * <p>{@code trustedProxies} 为受信任反向代理 IP 白名单；默认空列表，
 * 表示不信任任何客户端伪造的 {@code X-Forwarded-For} 请求头，直接使用 TCP 对端地址。
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
    boolean enabled,
    List<String> trustedProxies
) {

    public RateLimitProperties(boolean enabled, List<String> trustedProxies) {
        this.enabled = enabled;
        this.trustedProxies = trustedProxies == null
            ? List.of()
            : trustedProxies.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
    }

    public Set<String> trustedProxySet() {
        return trustedProxies.stream().collect(Collectors.toUnmodifiableSet());
    }

    public boolean isTrustedProxy(String remoteAddr) {
        return StringUtils.hasText(remoteAddr) && trustedProxySet().contains(remoteAddr.trim());
    }
}
