package com.shopcopilot.business.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.shopcopilot.business.common.BusinessException;
import com.shopcopilot.business.order.dto.OrderCreateRequest;
import com.shopcopilot.business.order.enums.OrderStatus;
import com.shopcopilot.business.order.mapper.OrderMapper;
import com.shopcopilot.business.order.service.OrderService;
import com.shopcopilot.business.order.vo.OrderVO;
import com.shopcopilot.business.product.cache.ProductCache;
import com.shopcopilot.business.product.dto.ProductSaveRequest;
import com.shopcopilot.business.product.entity.Product;
import com.shopcopilot.business.product.enums.ProductStatus;
import com.shopcopilot.business.product.mapper.ProductMapper;
import com.shopcopilot.business.product.service.ProductService;
import com.shopcopilot.business.product.vo.ProductVO;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

@SpringBootTest
class ProductCacheIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private OrderService orderService;

    @SpyBean
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    @AfterEach
    void clearCache() {
        Set<String> keys = redisTemplate.keys("shop:product:detail:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        clearInvocations(productMapper);
    }

    @Test
    @DisplayName("商品详情首次查询 MISS 回源写缓存，二次查询 HIT 不查库")
    void should_missThenHit_when_getProductTwice() {
        Product product = insertProduct("缓存测试耳机", 19900L, 50);
        try {
            assertNull(redisTemplate.opsForValue().get(ProductCache.key(product.getId())));

            ProductVO first = productService.getById(product.getId());
            assertEquals("缓存测试耳机", first.name());
            assertNotNull(redisTemplate.opsForValue().get(ProductCache.key(product.getId())));
            verify(productMapper, times(1)).selectById(product.getId());

            ProductVO second = productService.getById(product.getId());
            assertEquals(first, second);
            verify(productMapper, times(1)).selectById(product.getId());
        } finally {
            productMapper.deleteById(product.getId());
        }
    }

    @Test
    @DisplayName("商品更新立即失效缓存；下架后详情返回 404 且防穿透，重新上架后恢复可查")
    void should_evictCache_andBlockOffSaleDetail_when_updatedOrStatusChanged() {
        Product product = insertProduct("旧商品名称", 10000L, 20);
        try {
            ProductVO cached = productService.getById(product.getId());
            assertEquals("旧商品名称", cached.name());

            productService.update(product.getId(), new ProductSaveRequest(
                "新商品名称", "数码", "更新描述", 12000L, 30));
            assertNull(redisTemplate.opsForValue().get(ProductCache.key(product.getId())));

            ProductVO afterUpdate = productService.getById(product.getId());
            assertEquals("新商品名称", afterUpdate.name());
            assertEquals(12000L, afterUpdate.priceCents());
            assertEquals(30, afterUpdate.stock());

            productService.changeStatus(product.getId(), ProductStatus.OFF_SALE.getCode());
            assertNull(redisTemplate.opsForValue().get(ProductCache.key(product.getId())));

            clearInvocations(productMapper);
            BusinessException firstOffSale = assertThrows(BusinessException.class,
                () -> productService.getById(product.getId()));
            assertEquals(HttpStatus.NOT_FOUND, firstOffSale.getStatus());
            assertEquals("商品已下架", firstOffSale.getMessage());
            assertEquals(ProductCache.OFF_SALE_SENTINEL,
                redisTemplate.opsForValue().get(ProductCache.key(product.getId())));
            verify(productMapper, times(1)).selectById(product.getId());

            BusinessException secondOffSale = assertThrows(BusinessException.class,
                () -> productService.getById(product.getId()));
            assertEquals(HttpStatus.NOT_FOUND, secondOffSale.getStatus());
            verify(productMapper, times(1)).selectById(product.getId());

            productService.changeStatus(product.getId(), ProductStatus.ON_SALE.getCode());
            assertNull(redisTemplate.opsForValue().get(ProductCache.key(product.getId())));

            ProductVO reListed = productService.getById(product.getId());
            assertEquals(ProductStatus.ON_SALE.getCode(), reListed.status());
        } finally {
            productMapper.deleteById(product.getId());
        }
    }

    @Test
    @DisplayName("下单扣库存和取消恢复库存均失效商品缓存，不返回旧库存")
    void should_evictProductCache_when_orderCreatedAndCancelled() {
        Product product = insertProduct("库存联动商品", 5000L, 10);
        OrderVO order = null;
        try {
            assertEquals(10, productService.getById(product.getId()).stock());

            order = orderService.create(new OrderCreateRequest(
                product.getId(), 3, "郭克", "13800138000", "北京市朝阳区测试路 1 号"));
            assertNull(redisTemplate.opsForValue().get(ProductCache.key(product.getId())));
            assertEquals(7, productService.getById(product.getId()).stock());

            orderService.transition(order.id(), OrderStatus.CANCELLED);
            assertNull(redisTemplate.opsForValue().get(ProductCache.key(product.getId())));
            assertEquals(10, productService.getById(product.getId()).stock());
        } finally {
            if (order != null) {
                orderMapper.deleteById(order.id());
            }
            productMapper.deleteById(product.getId());
        }
    }

    @Test
    @DisplayName("不存在的商品写入空值哨兵防止缓存穿透")
    void should_cacheNullSentinel_when_productMissing() {
        long missingId = 99999999L;

        BusinessException first = assertThrows(BusinessException.class,
            () -> productService.getById(missingId));
        assertEquals(HttpStatus.NOT_FOUND, first.getStatus());
        assertEquals(ProductCache.NULL_SENTINEL,
            redisTemplate.opsForValue().get(ProductCache.key(missingId)));
        verify(productMapper, times(1)).selectById(missingId);

        BusinessException second = assertThrows(BusinessException.class,
            () -> productService.getById(missingId));
        assertEquals(HttpStatus.NOT_FOUND, second.getStatus());
        verify(productMapper, times(1)).selectById(missingId);
    }

    private Product insertProduct(String name, long priceCents, int stock) {
        Product product = new Product();
        product.setName(name);
        product.setCategory("数码");
        product.setDescription("缓存集成测试商品");
        product.setPriceCents(priceCents);
        product.setStock(stock);
        product.setStatus(ProductStatus.ON_SALE);
        product.setVersion(0);
        productMapper.insert(product);
        return product;
    }
}
