package com.shopcopilot.business.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.shopcopilot.business.order.entity.Order;
import com.shopcopilot.business.order.enums.OrderStatus;
import com.shopcopilot.business.order.mapper.OrderMapper;
import com.shopcopilot.business.product.entity.Product;
import com.shopcopilot.business.product.enums.ProductStatus;
import com.shopcopilot.business.product.mapper.ProductMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

@SpringBootTest
class OrderTransitionTransactionIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @SpyBean
    private OrderMapper orderMapperSpy;

    @Test
    @DisplayName("取消时订单更新失败则库存恢复必须回滚")
    void should_rollbackStockRestore_when_orderUpdateFails() {
        Product product = insertProduct();
        Order order = insertOrder(product);
        doThrow(new IllegalStateException("模拟订单更新失败"))
            .when(orderMapperSpy).updateById(any(Order.class));

        try {
            assertThrows(IllegalStateException.class,
                () -> orderService.transition(order.getId(), OrderStatus.CANCELLED));

            Product storedProduct = productMapper.selectById(product.getId());
            Order storedOrder = orderMapper.selectById(order.getId());
            assertEquals(10, storedProduct.getStock());
            assertEquals(0, storedProduct.getVersion());
            assertEquals(OrderStatus.CREATED, storedOrder.getStatus());
            assertEquals(0, storedOrder.getVersion());
            assertNull(storedOrder.getCancelledAt());
        } finally {
            orderMapper.deleteById(order.getId());
            productMapper.deleteById(product.getId());
        }
    }

    private Product insertProduct() {
        Product product = new Product();
        product.setName("取消回滚测试商品");
        product.setCategory("测试");
        product.setDescription("用于验证取消订单事务");
        product.setPriceCents(100L);
        product.setStock(10);
        product.setStatus(ProductStatus.OFF_SALE);
        product.setVersion(0);
        productMapper.insert(product);
        return product;
    }

    private Order insertOrder(Product product) {
        Order order = new Order();
        order.setOrderNo("SO_CANCEL_ROLLBACK");
        order.setUserId(10001L);
        order.setProductId(product.getId());
        order.setProductNameSnapshot(product.getName());
        order.setUnitPriceCents(product.getPriceCents());
        order.setQuantity(2);
        order.setTotalAmountCents(200L);
        order.setStatus(OrderStatus.CREATED);
        order.setVersion(0);
        order.setReceiverName("郭克");
        order.setReceiverPhone("13800138000");
        order.setShippingAddress("北京市朝阳区测试路 1 号");
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        orderMapper.insert(order);
        return order;
    }
}
