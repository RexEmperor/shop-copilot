package com.shopcopilot.business.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 商品缓存配置项。
 */
@ConfigurationProperties(prefix = "app.cache")
public record CacheProperties(
    long productTtlSeconds,
    long productTtlJitterSeconds,
    long productNullTtlSeconds
) {
}
