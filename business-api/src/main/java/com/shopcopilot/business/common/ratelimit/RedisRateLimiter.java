package com.shopcopilot.business.common.ratelimit;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis ZSET + Lua 脚本的原子滑动窗口限流器。
 *
 * <p>使用 Redis 服务端 {@code TIME} 作为统一时钟源消除多节点时钟漂移，
 * 每次请求在单次 Lua 事务内清理过期窗口元素、统计当前窗口请求数、
 * 仅在允许放行时写入当前请求并刷新过期时间，消除固定窗口临界突刺问题。
 */
@Component
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT = createScript();

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试在滑动窗口内获取一次请求配额；Redis 异常时降级放行以免阻断主链路。
     */
    public boolean tryAcquire(String key, int maxRequests, int windowSeconds) {
        if (maxRequests <= 0 || windowSeconds <= 0) {
            throw new IllegalArgumentException("限流阈值与窗口时间必须大于 0");
        }
        long windowMillis = windowSeconds * 1000L;
        String member = UUID.randomUUID().toString();
        try {
            Long current = redisTemplate.execute(
                SLIDING_WINDOW_SCRIPT,
                List.of(key),
                String.valueOf(windowMillis),
                String.valueOf(maxRequests),
                member
            );
            if (current == null) {
                log.warn("Rate limit script returned null, fail-open: key={}", key);
                return true;
            }
            if (current <= 0) {
                log.warn("Rate limit REJECTED (sliding-window): key={}, limit={}/{}s",
                    key, maxRequests, windowSeconds);
                return false;
            }
            log.debug("Rate limit ALLOWED (sliding-window): key={}, count={}, limit={}/{}s",
                key, current, maxRequests, windowSeconds);
            return true;
        } catch (DataAccessException exception) {
            log.warn("Rate limit Redis error, fail-open: key={}", key, exception);
            return true;
        }
    }

    private static DefaultRedisScript<Long> createScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local window = tonumber(ARGV[1])
            local limit = tonumber(ARGV[2])
            local member = ARGV[3]
            local window_start = now - window

            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', window_start)
            local current = redis.call('ZCARD', KEYS[1])
            if current >= limit then
                return 0
            end
            redis.call('ZADD', KEYS[1], now, member)
            redis.call('PEXPIRE', KEYS[1], window)
            return current + 1
            """);
        return script;
    }
}
