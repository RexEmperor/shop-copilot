package com.shopcopilot.business.product.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.shopcopilot.business.config.CacheProperties;
import com.shopcopilot.business.product.vo.ProductVO;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class ProductCacheTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private ObjectMapper objectMapper;
    private ProductCache productCache;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        productCache = new ProductCache(
            redisTemplate,
            objectMapper,
            new CacheProperties(300, 30, 60)
        );
    }

    @Test
    @DisplayName("缓存未命中返回 null")
    void should_returnNull_when_cacheMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("shop:product:detail:1")).thenReturn(null);

        assertNull(productCache.get(1L));
    }

    @Test
    @DisplayName("缓存命中空值哨兵返回 NOT_FOUND 条目")
    void should_returnNotFoundEntry_when_nullSentinelCached() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("shop:product:detail:99")).thenReturn(ProductCache.NULL_SENTINEL);

        ProductCache.CacheEntry entry = productCache.get(99L);
        assertNotNull(entry);
        assertEquals(ProductCache.EntryState.NOT_FOUND, entry.state());
        assertNull(entry.value());
    }

    @Test
    @DisplayName("缓存命中下架哨兵返回 OFF_SALE 条目")
    void should_returnOffSaleEntry_when_offSaleSentinelCached() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("shop:product:detail:10")).thenReturn(ProductCache.OFF_SALE_SENTINEL);

        ProductCache.CacheEntry entry = productCache.get(10L);
        assertNotNull(entry);
        assertEquals(ProductCache.EntryState.OFF_SALE, entry.state());
        assertNull(entry.value());
    }

    @Test
    @DisplayName("缓存中若意外存在下架商品 JSON 则自动删键并按 OFF_SALE 拦截")
    void should_evictAndReturnOffSale_when_cachedJsonIsOffSale() throws Exception {
        ProductVO offSale = sampleVO(10L, 0);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("shop:product:detail:10")).thenReturn(objectMapper.writeValueAsString(offSale));

        ProductCache.CacheEntry entry = productCache.get(10L);
        assertNotNull(entry);
        assertEquals(ProductCache.EntryState.OFF_SALE, entry.state());
        verify(redisTemplate).delete("shop:product:detail:10");
    }

    @Test
    @DisplayName("缓存命中有效上架商品 JSON 返回反序列化商品")
    void should_returnProduct_when_cacheHit() throws Exception {
        ProductVO vo = sampleVO(1L, 1);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("shop:product:detail:1")).thenReturn(objectMapper.writeValueAsString(vo));

        ProductCache.CacheEntry entry = productCache.get(1L);
        assertNotNull(entry);
        assertEquals(ProductCache.EntryState.HIT, entry.state());
        assertEquals(vo, entry.value());
    }

    @Test
    @DisplayName("缓存脏 JSON 时自动删除键并降级返回 null")
    void should_evictAndReturnNull_when_cachedJsonCorrupt() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("shop:product:detail:1")).thenReturn("{bad-json");

        assertNull(productCache.get(1L));
        verify(redisTemplate).delete("shop:product:detail:1");
    }

    @Test
    @DisplayName("Redis 读取异常时降级返回 null 不阻断主流程")
    void should_fallbackGracefully_when_redisUnavailable() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("shop:product:detail:1")).thenThrow(new QueryTimeoutException("timeout"));

        assertNull(productCache.get(1L));
    }

    @Test
    @DisplayName("写入缓存附加 TTL 抖动范围 [base, base + jitter]")
    void should_applyJitterTtl_when_puttingProduct() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        ProductVO vo = sampleVO(2L, 1);

        productCache.put(vo);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq("shop:product:detail:2"), any(String.class), ttlCaptor.capture());
        long seconds = ttlCaptor.getValue().getSeconds();
        assertTrue(seconds >= 300 && seconds <= 330);
    }

    @Test
    @DisplayName("失效缓存异常时仅记日志不抛异常")
    void should_notThrow_when_evictFails() {
        doThrow(new QueryTimeoutException("timeout")).when(redisTemplate).delete("shop:product:detail:1");

        productCache.evict(1L);
    }

    private ProductVO sampleVO(Long id, int status) {
        LocalDateTime now = LocalDateTime.of(2026, 11, 26, 12, 0, 0);
        return new ProductVO(
            id, "耳机", "数码", "降噪耳机", 19900L, 10, status, status == 1 ? "上架中" : "已下架", 0, now, now);
    }
}
