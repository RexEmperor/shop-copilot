package com.shopcopilot.business.product.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopcopilot.business.config.CacheProperties;
import com.shopcopilot.business.product.enums.ProductStatus;
import com.shopcopilot.business.product.vo.ProductVO;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 商品详情 Cache-aside 缓存组件。
 *
 * <p>同时承担防穿透（空值/下架短 TTL 哨兵）与防雪崩（TTL 随机抖动）保护，
 * 并在事务提交后再次删键以降低并发读写下的脏缓存窗口。
 */
@Component
public class ProductCache {

    private static final Logger log = LoggerFactory.getLogger(ProductCache.class);
    private static final String KEY_PREFIX = "shop:product:detail:";
    public static final String NULL_SENTINEL = "__NULL__";
    public static final String OFF_SALE_SENTINEL = "__OFF_SALE__";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CacheProperties properties;

    public ProductCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            CacheProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 读取缓存；未命中或缓存异常时返回 null，由调用方回源数据库。
     */
    public CacheEntry get(Long productId) {
        String key = key(productId);
        try {
            String raw = redisTemplate.opsForValue().get(key);
            if (raw == null) {
                log.info("Product cache MISS: productId={}", productId);
                return null;
            }
            if (NULL_SENTINEL.equals(raw)) {
                log.info("Product cache HIT (null): productId={}", productId);
                return CacheEntry.notFound();
            }
            if (OFF_SALE_SENTINEL.equals(raw)) {
                log.info("Product cache HIT (off-sale): productId={}", productId);
                return CacheEntry.offSale();
            }
            ProductVO vo = objectMapper.readValue(raw, ProductVO.class);
            if (vo.status() == null || vo.status() != ProductStatus.ON_SALE.getCode()) {
                log.warn("Product cache contained non-on-sale item, evicting: productId={}", productId);
                deleteKey(productId, "non-on-sale");
                return CacheEntry.offSale();
            }
            log.info("Product cache HIT: productId={}", productId);
            return CacheEntry.of(vo);
        } catch (JsonProcessingException exception) {
            log.warn("Product cache deserialize failed, evicting key={}", key, exception);
            deleteKey(productId, "corrupt");
            return null;
        } catch (DataAccessException exception) {
            log.warn("Product cache read failed, fallback to DB: productId={}", productId, exception);
            return null;
        }
    }

    /**
     * 写入上架商品详情缓存，并附加随机抖动 TTL 避免同时过期。
     */
    public void put(ProductVO product) {
        long baseTtl = properties.productTtlSeconds();
        if (baseTtl <= 0) {
            return;
        }
        long jitterBound = Math.max(0L, properties.productTtlJitterSeconds());
        long jitter = jitterBound == 0 ? 0 : ThreadLocalRandom.current().nextLong(jitterBound + 1);
        long ttlSeconds = baseTtl + jitter;
        try {
            String json = objectMapper.writeValueAsString(product);
            redisTemplate.opsForValue().set(key(product.id()), json, Duration.ofSeconds(ttlSeconds));
        } catch (JsonProcessingException | DataAccessException exception) {
            log.warn("Product cache write failed: productId={}", product.id(), exception);
        }
    }

    /**
     * 写入空值哨兵，防止不存在的商品 ID 反复穿透到数据库。
     */
    public void putNull(Long productId) {
        putSentinel(productId, NULL_SENTINEL, "null");
    }

    /**
     * 写入下架哨兵，防止高频访问已下架商品详情穿透到数据库。
     */
    public void putOffSale(Long productId) {
        putSentinel(productId, OFF_SALE_SENTINEL, "off-sale");
    }

    private void putSentinel(Long productId, String sentinel, String label) {
        long nullTtl = properties.productNullTtlSeconds();
        if (nullTtl <= 0) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                key(productId), sentinel, Duration.ofSeconds(nullTtl));
        } catch (DataAccessException exception) {
            log.warn("Product {}-cache write failed: productId={}", label, productId, exception);
        }
    }

    /**
     * 立即失效缓存；若处于活跃事务中，则在事务提交后再次删键兜底。
     */
    public void evict(Long productId) {
        deleteKey(productId, "immediate");
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteKey(productId, "after-commit");
                }
            });
        }
    }

    private void deleteKey(Long productId, String phase) {
        try {
            Boolean deleted = redisTemplate.delete(key(productId));
            log.info("Product cache EVICT ({}): productId={}, deleted={}", phase, productId, deleted);
        } catch (DataAccessException exception) {
            log.warn("Product cache evict failed ({}): productId={}", phase, productId, exception);
        }
    }

    public static String key(Long productId) {
        return KEY_PREFIX + productId;
    }

    public enum EntryState {
        HIT,
        NOT_FOUND,
        OFF_SALE
    }

    public record CacheEntry(ProductVO value, EntryState state) {

        public static CacheEntry of(ProductVO value) {
            return new CacheEntry(value, EntryState.HIT);
        }

        public static CacheEntry notFound() {
            return new CacheEntry(null, EntryState.NOT_FOUND);
        }

        public static CacheEntry offSale() {
            return new CacheEntry(null, EntryState.OFF_SALE);
        }
    }
}
