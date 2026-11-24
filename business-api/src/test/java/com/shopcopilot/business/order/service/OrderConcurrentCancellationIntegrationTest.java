package com.shopcopilot.business.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.shopcopilot.business.common.BusinessException;
import com.shopcopilot.business.order.entity.Order;
import com.shopcopilot.business.order.enums.OrderStatus;
import com.shopcopilot.business.order.mapper.OrderMapper;
import com.shopcopilot.business.product.entity.Product;
import com.shopcopilot.business.product.enums.ProductStatus;
import com.shopcopilot.business.product.mapper.ProductMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(OrderConcurrentCancellationIntegrationTest.OrderReadBarrierConfiguration.class)
class OrderConcurrentCancellationIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderReadBarrier orderReadBarrier;

    @Test
    @DisplayName("并发取消同一订单时库存只恢复一次")
    void should_restoreStockOnlyOnce_when_cancellationConcurrent() throws Exception {
        Product product = insertProduct();
        Order order = insertOrder(product);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Callable<String> cancel = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    orderService.transition(order.getId(), OrderStatus.CANCELLED);
                    return "SUCCESS";
                } catch (BusinessException exception) {
                    return exception.getStatus().name();
                } catch (Exception exception) {
                    return "ERROR:" + exception.getClass().getSimpleName();
                }
            };

            Future<String> first = executor.submit(cancel);
            Future<String> second = executor.submit(cancel);
            List<String> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertEquals(List.of("CONFLICT", "SUCCESS"), results.stream().sorted().toList());
            assertEquals(List.of(0, 0), orderReadBarrier.readVersions());

            Product storedProduct = productMapper.selectById(product.getId());
            Order storedOrder = orderMapper.selectById(order.getId());
            assertEquals(12, storedProduct.getStock());
            assertEquals(1, storedProduct.getVersion());
            assertEquals(OrderStatus.CANCELLED, storedOrder.getStatus());
            assertEquals(1, storedOrder.getVersion());
            assertNotNull(storedOrder.getCancelledAt());
        } finally {
            executor.shutdownNow();
            orderMapper.deleteById(order.getId());
            productMapper.deleteById(product.getId());
        }
    }

    private Product insertProduct() {
        Product product = new Product();
        product.setName("并发取消测试商品");
        product.setCategory("测试");
        product.setDescription("用于验证并发取消只恢复一次库存");
        product.setPriceCents(100L);
        product.setStock(10);
        product.setStatus(ProductStatus.OFF_SALE);
        product.setVersion(0);
        productMapper.insert(product);
        return product;
    }

    private Order insertOrder(Product product) {
        Order order = new Order();
        order.setOrderNo("SO_CONCURRENT_" + UUID.randomUUID().toString().substring(0, 8));
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

    @TestConfiguration(proxyBeanMethods = false)
    static class OrderReadBarrierConfiguration {

        @Bean
        OrderReadBarrier orderReadBarrier() {
            return new OrderReadBarrier();
        }

        @Bean
        ConfigurationCustomizer orderReadBarrierCustomizer(OrderReadBarrier orderReadBarrier) {
            return configuration -> configuration.addInterceptor(orderReadBarrier);
        }
    }

    @Intercepts({
        @Signature(
            type = Executor.class,
            method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}
        )
    })
    static class OrderReadBarrier implements Interceptor {

        private static final String ORDER_SELECT_BY_ID =
            "com.shopcopilot.business.order.mapper.OrderMapper.selectById";
        private final CountDownLatch orderReads = new CountDownLatch(2);
        private final List<Integer> readVersions = new CopyOnWriteArrayList<>();

        List<Integer> readVersions() {
            return List.copyOf(readVersions);
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            if (ORDER_SELECT_BY_ID.equals(statement.getId())) {
                Order loadedOrder = (Order) ((List<?>) result).get(0);
                readVersions.add(loadedOrder.getVersion());
                orderReads.countDown();
                if (!orderReads.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("两个事务未在更新前完成订单读取");
                }
            }
            return result;
        }
    }
}
