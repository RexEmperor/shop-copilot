package com.shopcopilot.business.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.shopcopilot.business.order.dto.OrderCreateRequest;
import com.shopcopilot.business.order.entity.Order;
import com.shopcopilot.business.order.mapper.OrderMapper;
import com.shopcopilot.business.product.entity.Product;
import com.shopcopilot.business.product.enums.ProductStatus;
import com.shopcopilot.business.product.mapper.ProductMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

@SpringBootTest
class OrderCreationTransactionIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @SpyBean
    private OrderMapper orderMapperSpy;

    @Test
    @DisplayName("订单插入失败时库存扣减必须随事务回滚")
    void should_rollbackStockDeduction_when_orderInsertFails() {
        Product product = insertProduct();
        long orderCountBefore = orderMapper.selectCount(null);
        doThrow(new IllegalStateException("模拟订单插入失败"))
            .when(orderMapperSpy).insert(any(Order.class));

        try {
            assertThrows(IllegalStateException.class, () -> orderService.create(new OrderCreateRequest(
                product.getId(), 2, "郭克", "13800138000", "北京市朝阳区测试路 1 号")));

            Product stored = productMapper.selectById(product.getId());
            assertEquals(10, stored.getStock());
            assertEquals(0, stored.getVersion());
            assertEquals(orderCountBefore, orderMapper.selectCount(null));
        } finally {
            productMapper.deleteById(product.getId());
        }
    }

    private Product insertProduct() {
        Product product = new Product();
        product.setName("事务回滚测试商品");
        product.setCategory("测试");
        product.setDescription("用于验证订单创建事务");
        product.setPriceCents(100L);
        product.setStock(10);
        product.setStatus(ProductStatus.ON_SALE);
        product.setVersion(0);
        productMapper.insert(product);
        return product;
    }
}
