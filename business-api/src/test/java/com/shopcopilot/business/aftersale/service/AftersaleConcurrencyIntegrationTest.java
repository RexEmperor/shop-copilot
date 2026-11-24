package com.shopcopilot.business.aftersale.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.shopcopilot.business.aftersale.dto.AftersaleCreateRequest;
import com.shopcopilot.business.aftersale.entity.AftersaleTicket;
import com.shopcopilot.business.aftersale.enums.AftersaleStatus;
import com.shopcopilot.business.aftersale.mapper.AftersaleTicketMapper;
import com.shopcopilot.business.common.BusinessException;
import com.shopcopilot.business.order.entity.Order;
import com.shopcopilot.business.order.enums.OrderStatus;
import com.shopcopilot.business.order.mapper.OrderMapper;
import com.shopcopilot.business.order.service.OrderService;
import com.shopcopilot.business.product.entity.Product;
import com.shopcopilot.business.product.enums.ProductStatus;
import com.shopcopilot.business.product.mapper.ProductMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(AftersaleConcurrencyIntegrationTest.ConcurrencyBarrierConfiguration.class)
class AftersaleConcurrencyIntegrationTest {

    @Autowired
    private AftersaleService aftersaleService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private AftersaleTicketMapper aftersaleMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ConcurrencyBarrier concurrencyBarrier;

    @Test
    @DisplayName("同一订单并发发起售后只生成一条进行中工单")
    void should_createOnlyOneActiveTicket_when_creationConcurrent() throws Exception {
        Product product = null;
        Order order = null;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        concurrencyBarrier.resetNone();

        try {
            product = insertProduct();
            order = insertOrder(product);
            Order createdOrder = order;
            AftersaleCreateRequest request = new AftersaleCreateRequest(
                createdOrder.getOrderNo(), 0, "并发创建测试");
            concurrencyBarrier.resetCreate();
            Callable<String> create = () -> concurrencyBarrier.runCreate(aftersaleService, request);

            Future<String> first = executor.submit(create);
            assertTrue(concurrencyBarrier.awaitFirstCount());

            Future<String> second = executor.submit(create);
            assertTrue(concurrencyBarrier.awaitSecondAttempt());
            assertFalse(second.isDone());

            concurrencyBarrier.allowFirstCreateToFinish();
            List<String> results = List.of(
                first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertEquals(List.of("CONFLICT", "SUCCESS"), results.stream().sorted().toList());
            QueryWrapper<AftersaleTicket> query = new QueryWrapper<AftersaleTicket>()
                .eq("order_no", createdOrder.getOrderNo());
            assertEquals(1L, aftersaleMapper.selectCount(query));
            assertEquals(2, concurrencyBarrier.activeCountReads());
        } finally {
            concurrencyBarrier.allowFirstCreateToFinish();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            if (order != null) {
                aftersaleMapper.delete(new QueryWrapper<AftersaleTicket>()
                    .eq("order_no", order.getOrderNo()));
                orderMapper.deleteById(order.getId());
            }
            if (product != null) {
                productMapper.deleteById(product.getId());
            }
        }
    }

    @Test
    @DisplayName("售后提交后并发取消订单必须看到已提交售后")
    void should_blockCancellation_when_aftersaleCommitsWhileCancellationWaits() throws Exception {
        Product product = null;
        Order order = null;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        concurrencyBarrier.resetNone();

        try {
            product = insertProduct();
            order = insertOrder(product);
            Order createdOrder = order;
            AftersaleCreateRequest request = new AftersaleCreateRequest(
                createdOrder.getOrderNo(), 0, "创建与取消竞态测试");
            concurrencyBarrier.resetCancelRace();

            Future<String> creation = executor.submit(
                () -> concurrencyBarrier.runCreate(aftersaleService, request));
            assertTrue(concurrencyBarrier.awaitCreationLocked());

            Future<String> cancellation = executor.submit(
                () -> concurrencyBarrier.runCancel(orderService, createdOrder.getId()));
            assertTrue(concurrencyBarrier.awaitCancellationAttempted());
            assertFalse(cancellation.isDone());

            concurrencyBarrier.allowFirstCreateToFinish();
            List<String> results = List.of(
                creation.get(10, TimeUnit.SECONDS), cancellation.get(10, TimeUnit.SECONDS));

            assertEquals(List.of("CONFLICT", "SUCCESS"), results.stream().sorted().toList());
            assertEquals(OrderStatus.PAID, orderMapper.selectById(createdOrder.getId()).getStatus());
            assertEquals(10, productMapper.selectById(product.getId()).getStock());
            QueryWrapper<AftersaleTicket> query = new QueryWrapper<AftersaleTicket>()
                .eq("order_no", createdOrder.getOrderNo());
            assertEquals(1L, aftersaleMapper.selectCount(query));
        } finally {
            concurrencyBarrier.allowFirstCreateToFinish();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            if (order != null) {
                aftersaleMapper.delete(new QueryWrapper<AftersaleTicket>()
                    .eq("order_no", order.getOrderNo()));
                orderMapper.deleteById(order.getId());
            }
            if (product != null) {
                productMapper.deleteById(product.getId());
            }
        }
    }

    @Test
    @DisplayName("同一工单并发审核只有一个成功")
    void should_approveOnlyOnce_when_reviewConcurrent() throws Exception {
        Product product = null;
        Order order = null;
        Long ticketId = null;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        concurrencyBarrier.resetNone();

        try {
            product = insertProduct();
            order = insertOrder(product);
            ticketId = aftersaleService.create(new AftersaleCreateRequest(
                order.getOrderNo(), 0, "并发审核测试"));
            concurrencyBarrier.resetTransition();
            Long reviewedTicketId = ticketId;
            Callable<String> approve = () -> concurrencyBarrier.runTransition(
                () -> aftersaleService.approve(reviewedTicketId));

            List<String> results = runConcurrently(executor, approve);

            assertEquals(List.of("CONFLICT", "SUCCESS"), results.stream().sorted().toList());
            assertEquals(List.of(AftersaleStatus.PENDING, AftersaleStatus.PENDING),
                concurrencyBarrier.transitionStatuses());
            AftersaleTicket stored = aftersaleMapper.selectById(ticketId);
            assertEquals(AftersaleStatus.REFUNDING, stored.getStatus());
            assertNotNull(stored.getReviewedAt());
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            if (ticketId != null) {
                aftersaleMapper.deleteById(ticketId);
            }
            if (order != null) {
                orderMapper.deleteById(order.getId());
            }
            if (product != null) {
                productMapper.deleteById(product.getId());
            }
        }
    }

    @Test
    @DisplayName("同一工单并发确认退款只有一个成功")
    void should_completeRefundOnlyOnce_when_confirmationConcurrent() throws Exception {
        Product product = null;
        Order order = null;
        Long ticketId = null;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        concurrencyBarrier.resetNone();

        try {
            product = insertProduct();
            order = insertOrder(product);
            ticketId = aftersaleService.create(new AftersaleCreateRequest(
                order.getOrderNo(), 0, "并发退款测试"));
            aftersaleService.approve(ticketId);
            concurrencyBarrier.resetTransition();
            Long refundedTicketId = ticketId;
            Callable<String> complete = () -> concurrencyBarrier.runTransition(
                () -> aftersaleService.completeRefund(refundedTicketId));

            List<String> results = runConcurrently(executor, complete);

            assertEquals(List.of("CONFLICT", "SUCCESS"), results.stream().sorted().toList());
            assertEquals(List.of(AftersaleStatus.REFUNDING, AftersaleStatus.REFUNDING),
                concurrencyBarrier.transitionStatuses());
            AftersaleTicket stored = aftersaleMapper.selectById(ticketId);
            assertEquals(AftersaleStatus.REFUNDED, stored.getStatus());
            assertNotNull(stored.getRefundedAt());
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            if (ticketId != null) {
                aftersaleMapper.deleteById(ticketId);
            }
            if (order != null) {
                orderMapper.deleteById(order.getId());
            }
            if (product != null) {
                productMapper.deleteById(product.getId());
            }
        }
    }

    private List<String> runConcurrently(ExecutorService executor, Callable<String> action)
            throws Exception {
        Future<String> first = executor.submit(action);
        Future<String> second = executor.submit(action);
        return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    }

    private Product insertProduct() {
        Product product = new Product();
        product.setName("售后并发测试商品");
        product.setCategory("测试");
        product.setDescription("用于验证售后并发控制");
        product.setPriceCents(100L);
        product.setStock(10);
        product.setStatus(ProductStatus.OFF_SALE);
        product.setVersion(0);
        productMapper.insert(product);
        return product;
    }

    private Order insertOrder(Product product) {
        Order order = new Order();
        order.setOrderNo("SO_AFTERSALE_CONCURRENT_" + UUID.randomUUID().toString().substring(0, 8));
        order.setUserId(10001L);
        order.setProductId(product.getId());
        order.setProductNameSnapshot(product.getName());
        order.setUnitPriceCents(100L);
        order.setQuantity(2);
        order.setTotalAmountCents(200L);
        order.setStatus(OrderStatus.PAID);
        order.setVersion(0);
        order.setReceiverName("郭克");
        order.setReceiverPhone("13800138000");
        order.setShippingAddress("北京市朝阳区测试路 1 号");
        LocalDateTime now = LocalDateTime.now();
        order.setCreatedAt(now.minusDays(1));
        order.setUpdatedAt(now);
        order.setPaidAt(now.minusHours(12));
        orderMapper.insert(order);
        return order;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ConcurrencyBarrierConfiguration {

        @Bean
        ConcurrencyBarrier concurrencyBarrier() {
            return new ConcurrencyBarrier();
        }

        @Bean
        ConfigurationCustomizer concurrencyBarrierCustomizer(ConcurrencyBarrier concurrencyBarrier) {
            return configuration -> configuration.addInterceptor(concurrencyBarrier);
        }
    }

    @Intercepts({
        @Signature(
            type = Executor.class,
            method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}
        )
    })
    static class ConcurrencyBarrier implements Interceptor {

        private static final String ORDER_SELECT_FOR_UPDATE =
            "com.shopcopilot.business.order.mapper.OrderMapper.selectByOrderNoForUpdate";
        private static final String TICKET_SELECT_BY_ID =
            "com.shopcopilot.business.aftersale.mapper.AftersaleTicketMapper.selectById";
        private static final String COUNT_ACTIVE_BY_ORDER_NO =
            "com.shopcopilot.business.aftersale.mapper.AftersaleTicketMapper.countActiveByOrderNo";

        private enum Mode {
            NONE,
            CREATE,
            TRANSITION,
            CANCEL_RACE
        }

        private volatile Mode mode = Mode.NONE;
        private final AtomicInteger orderSelects = new AtomicInteger();
        private final AtomicInteger activeCounts = new AtomicInteger();
        private final AtomicInteger cancelRaceOrderSelects = new AtomicInteger();
        private volatile CountDownLatch firstCounted;
        private volatile CountDownLatch allowFirstCreate;
        private volatile CountDownLatch secondAttempted;
        private volatile CountDownLatch creationLocked;
        private volatile CountDownLatch cancellationAttempted;
        private volatile CountDownLatch transitionReads;
        private volatile List<AftersaleStatus> statuses;

        void resetNone() {
            mode = Mode.NONE;
        }

        void resetCreate() {
            mode = Mode.CREATE;
            orderSelects.set(0);
            activeCounts.set(0);
            firstCounted = new CountDownLatch(1);
            allowFirstCreate = new CountDownLatch(1);
            secondAttempted = new CountDownLatch(1);
        }

        void resetCancelRace() {
            mode = Mode.CANCEL_RACE;
            cancelRaceOrderSelects.set(0);
            allowFirstCreate = new CountDownLatch(1);
            creationLocked = new CountDownLatch(1);
            cancellationAttempted = new CountDownLatch(1);
        }

        boolean awaitCreationLocked() throws InterruptedException {
            return creationLocked.await(5, TimeUnit.SECONDS);
        }

        boolean awaitCancellationAttempted() throws InterruptedException {
            return cancellationAttempted.await(5, TimeUnit.SECONDS);
        }

        void resetTransition() {
            mode = Mode.TRANSITION;
            transitionReads = new CountDownLatch(2);
            statuses = new CopyOnWriteArrayList<>();
        }

        boolean awaitFirstCount() throws InterruptedException {
            return firstCounted.await(5, TimeUnit.SECONDS);
        }

        boolean awaitSecondAttempt() throws InterruptedException {
            return secondAttempted.await(5, TimeUnit.SECONDS);
        }

        void allowFirstCreateToFinish() {
            allowFirstCreate.countDown();
        }

        int activeCountReads() {
            return activeCounts.get();
        }

        List<AftersaleStatus> transitionStatuses() {
            return List.copyOf(statuses);
        }

        String runCreate(AftersaleService service, AftersaleCreateRequest request) {
            try {
                service.create(request);
                return "SUCCESS";
            } catch (BusinessException exception) {
                return exception.getStatus().name();
            } catch (Exception exception) {
                return "ERROR:" + exception.getClass().getSimpleName();
            }
        }

        String runCancel(OrderService service, Long orderId) {
            try {
                service.transition(orderId, OrderStatus.CANCELLED);
                return "SUCCESS";
            } catch (BusinessException exception) {
                return exception.getStatus().name();
            } catch (Exception exception) {
                return "ERROR:" + exception.getClass().getSimpleName();
            }
        }

        String runTransition(Runnable action) {
            try {
                action.run();
                return "SUCCESS";
            } catch (BusinessException exception) {
                return exception.getStatus().name();
            } catch (Exception exception) {
                return "ERROR:" + exception.getClass().getSimpleName();
            }
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            String statementId = statement.getId();

            if (mode == Mode.CANCEL_RACE && ORDER_SELECT_FOR_UPDATE.equals(statementId)) {
                int attempt = cancelRaceOrderSelects.incrementAndGet();
                if (attempt == 1) {
                    Object lockedOrder = invocation.proceed();
                    creationLocked.countDown();
                    if (!allowFirstCreate.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("创建事务未在取消等待期间保持订单锁");
                    }
                    return lockedOrder;
                }
                if (attempt == 2) {
                    cancellationAttempted.countDown();
                }
            }

            if (mode == Mode.CREATE && ORDER_SELECT_FOR_UPDATE.equals(statementId)
                    && orderSelects.incrementAndGet() == 2) {
                secondAttempted.countDown();
            }

            Object result = invocation.proceed();

            if (mode == Mode.CREATE && COUNT_ACTIVE_BY_ORDER_NO.equals(statementId)
                    && activeCounts.incrementAndGet() == 1) {
                firstCounted.countDown();
                if (!allowFirstCreate.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("第一个创建事务未在释放锁前完成计数");
                }
            }

            if (mode == Mode.TRANSITION && TICKET_SELECT_BY_ID.equals(statementId)) {
                AftersaleTicket ticket = (AftersaleTicket) ((List<?>) result).get(0);
                statuses.add(ticket.getStatus());
                transitionReads.countDown();
                if (!transitionReads.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("两个事务未在更新前完成售后读取");
                }
            }

            return result;
        }
    }
}
