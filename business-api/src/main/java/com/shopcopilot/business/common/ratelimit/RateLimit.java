package com.shopcopilot.business.common.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 基于 Redis ZSET 滑动窗口原子计数的接口限流注解。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 时间窗口内允许的最大请求数。
     */
    int maxRequests() default 60;

    /**
     * 时间窗口长度（秒）。
     */
    int windowSeconds() default 60;

    /**
     * 限流键前缀；为空时按控制器类名与方法名自动生成。
     */
    String keyPrefix() default "";
}
