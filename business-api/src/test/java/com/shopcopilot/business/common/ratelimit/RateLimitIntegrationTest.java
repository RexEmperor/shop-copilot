package com.shopcopilot.business.common.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shopcopilot.business.common.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@Import(RateLimitIntegrationTest.RateLimitTestControllerConfig.class)
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    @AfterEach
    void clearRateLimitKeys() {
        Set<String> keys = redisTemplate.keys("shop:ratelimit:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("默认不信任 X-Forwarded-For，伪造请求头无法绕过限流；不同直连 IP 互不影响")
    void should_ignoreSpoofedForwardedFor_andIsolateByRemoteAddr() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/test/rate-limit/burst")
                    .with(remoteAddr("10.0.0.1"))
                    .header("X-Forwarded-For", "203.0.113." + i))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        }

        mockMvc.perform(get("/api/test/rate-limit/burst")
                .with(remoteAddr("10.0.0.1"))
                .header("X-Forwarded-For", "203.0.113.99"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value(429))
            .andExpect(jsonPath("$.message").value("请求过于频繁，请稍后再试"));

        mockMvc.perform(get("/api/test/rate-limit/burst")
                .with(remoteAddr("10.0.0.2")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("ZSET 滑动窗口消除临界突刺：仅滑出窗口的配额会释放，窗口内请求继续计入")
    void should_slideWindowGradually_withoutBoundaryBurst() throws Exception {
        // 1 秒窗口，最多 3 次
        mockMvc.perform(get("/api/test/rate-limit/sliding").with(remoteAddr("10.0.0.50")))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/test/rate-limit/sliding").with(remoteAddr("10.0.0.50")))
            .andExpect(status().isOk());

        Thread.sleep(600);

        // t≈600ms：第 3 次放行，第 4 次被拒（且不写入 ZSET）
        mockMvc.perform(get("/api/test/rate-limit/sliding").with(remoteAddr("10.0.0.50")))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/test/rate-limit/sliding").with(remoteAddr("10.0.0.50")))
            .andExpect(status().isTooManyRequests());

        Thread.sleep(500);

        // t≈1100ms：t=0 的 2 次已滑出 1000ms 窗口，但 t≈600ms 的 1 次仍在窗口内
        // 所以最多只能再放行 2 次，第 3 次必须返回 429
        mockMvc.perform(get("/api/test/rate-limit/sliding").with(remoteAddr("10.0.0.50")))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/test/rate-limit/sliding").with(remoteAddr("10.0.0.50")))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/test/rate-limit/sliding").with(remoteAddr("10.0.0.50")))
            .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("并发突发请求下 Redis ZSET Lua 原子计数精确放行 5 次拒绝 15 次")
    void should_countAtomically_when_requestsConcurrent() throws Exception {
        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();

        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    int statusCode = mockMvc.perform(get("/api/test/rate-limit/burst")
                            .with(remoteAddr("10.0.0.99")))
                        .andReturn()
                        .getResponse()
                        .getStatus();
                    if (statusCode == 200) {
                        okCount.incrementAndGet();
                    } else if (statusCode == 429) {
                        rejectedCount.incrementAndGet();
                    }
                    return statusCode;
                });
            }

            List<Future<Integer>> futures = executor.invokeAll(tasks, 10, TimeUnit.SECONDS);
            for (Future<Integer> future : futures) {
                future.get();
            }

            assertEquals(5, okCount.get());
            assertEquals(15, rejectedCount.get());

            String key = "shop:ratelimit:test-burst:10.0.0.99";
            assertEquals(DataType.ZSET, redisTemplate.type(key));
            assertEquals(5L, redisTemplate.opsForZSet().zCard(key));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("真实商品详情接口接入滑动窗口限流并在 Redis 中生成 ZSET 计数键")
    void should_trackRateLimitInRedis_for_productEndpoint() throws Exception {
        mockMvc.perform(get("/api/products/1").with(remoteAddr("10.0.0.88")))
            .andExpect(status().isOk());

        String key = "shop:ratelimit:ProductController#detail:10.0.0.88";
        assertEquals(DataType.ZSET, redisTemplate.type(key));
        assertEquals(1L, redisTemplate.opsForZSet().zCard(key));
    }

    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RateLimitTestControllerConfig {

        @Bean
        RateLimitTestController rateLimitTestController() {
            return new RateLimitTestController();
        }
    }

    @RestController
    @RequestMapping("/api/test/rate-limit")
    static class RateLimitTestController {

        @GetMapping("/burst")
        @RateLimit(maxRequests = 5, windowSeconds = 10, keyPrefix = "test-burst")
        public Result<String> burst() {
            return Result.ok("pong");
        }

        @GetMapping("/sliding")
        @RateLimit(maxRequests = 3, windowSeconds = 1, keyPrefix = "test-sliding")
        public Result<String> sliding() {
            return Result.ok("pong");
        }
    }
}
