package com.shopcopilot.business.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shopcopilot.business.aftersale.mapper.AftersaleTicketMapper;
import com.shopcopilot.business.common.BusinessException;
import com.shopcopilot.business.order.entity.Order;
import com.shopcopilot.business.order.enums.OrderStatus;
import com.shopcopilot.business.order.mapper.OrderMapper;
import com.shopcopilot.business.order.service.impl.OrderServiceImpl;
import com.shopcopilot.business.order.state.OrderStateMachine;
import com.shopcopilot.business.order.vo.OrderVO;
import com.shopcopilot.business.product.cache.ProductCache;
import com.shopcopilot.business.product.mapper.ProductMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class OrderStateTransitionServiceTest {

    @Mock
    private ProductMapper productMapper;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private AftersaleTicketMapper aftersaleTicketMapper;

    @Mock
    private ProductCache productCache;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl(
            productMapper, orderMapper, new OrderStateMachine(), aftersaleTicketMapper, productCache);
    }

    @Test
    @DisplayName("待支付订单支付后写入支付时间")
    void should_pay_when_orderCreated() {
        when(orderMapper.selectById(1L)).thenReturn(order(1L, OrderStatus.CREATED));
        when(orderMapper.updateById(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setVersion(order.getVersion() + 1);
            return 1;
        });

        OrderVO result = orderService.transition(1L, OrderStatus.PAID);

        assertEquals(1, result.status());
        assertEquals("已支付", result.statusText());
        assertEquals(1, result.version());
        assertNotNull(result.paidAt());
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).updateById(captor.capture());
        assertEquals(OrderStatus.PAID, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getPaidAt());
        assertNull(captor.getValue().getShippedAt());
        verifyNoInteractions(productMapper);
    }

    @Test
    @DisplayName("已支付订单发货后写入发货时间")
    void should_ship_when_orderPaid() {
        when(orderMapper.selectById(1L)).thenReturn(order(1L, OrderStatus.PAID));
        when(orderMapper.updateById(any(Order.class))).thenReturn(1);

        OrderVO result = orderService.transition(1L, OrderStatus.SHIPPED);

        assertEquals(2, result.status());
        assertEquals("已发货", result.statusText());
        assertNotNull(result.shippedAt());
        assertNotNull(result.paidAt());
        assertNull(result.deliveredAt());
        verifyNoInteractions(productMapper);
    }

    @Test
    @DisplayName("已发货订单确认收货后写入完成时间")
    void should_deliver_when_orderShipped() {
        when(orderMapper.selectById(1L)).thenReturn(order(1L, OrderStatus.SHIPPED));
        when(orderMapper.updateById(any(Order.class))).thenReturn(1);

        OrderVO result = orderService.transition(1L, OrderStatus.DELIVERED);

        assertEquals(3, result.status());
        assertEquals("已完成", result.statusText());
        assertNotNull(result.deliveredAt());
        assertNotNull(result.shippedAt());
        verifyNoInteractions(productMapper);
    }

    @Test
    @DisplayName("取消订单时恢复库存并写入取消时间")
    void should_cancelAndRestoreStock_when_orderNotTerminal() {
        when(orderMapper.selectById(1L)).thenReturn(order(1L, OrderStatus.CREATED));
        when(orderMapper.selectByOrderNoForUpdate("SO202611240001"))
            .thenReturn(order(1L, OrderStatus.CREATED));
        when(aftersaleTicketMapper.findBlockingIdByOrderNoForUpdate("SO202611240001"))
            .thenReturn(null);
        when(productMapper.restoreStock(1L, 2)).thenReturn(1);
        when(orderMapper.updateById(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setVersion(order.getVersion() + 1);
            return 1;
        });

        OrderVO result = orderService.transition(1L, OrderStatus.CANCELLED);

        assertEquals(4, result.status());
        assertEquals("已取消", result.statusText());
        assertEquals(1, result.version());
        assertNotNull(result.cancelledAt());
        verify(productMapper).restoreStock(1L, 2);
        verify(orderMapper).updateById(any(Order.class));
    }

    @Test
    @DisplayName("订单不存在时返回 404")
    void should_throwNotFound_when_orderMissing() {
        when(orderMapper.selectById(999L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> orderService.transition(999L, OrderStatus.PAID));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("订单不存在", exception.getMessage());
        verify(orderMapper, never()).updateById(any(Order.class));
        verifyNoInteractions(productMapper);
    }

    @Test
    @DisplayName("跳过支付直接发货时返回 409 且不更新订单")
    void should_throwConflict_when_transitionSkipped() {
        when(orderMapper.selectById(1L)).thenReturn(order(1L, OrderStatus.CREATED));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> orderService.transition(1L, OrderStatus.SHIPPED));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("订单状态不能从待支付变更为已发货", exception.getMessage());
        verify(orderMapper, never()).updateById(any(Order.class));
        verifyNoInteractions(productMapper);
    }

    @Test
    @DisplayName("终态订单不能继续流转")
    void should_throwConflict_when_orderTerminal() {
        when(orderMapper.selectById(1L)).thenReturn(order(1L, OrderStatus.DELIVERED));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> orderService.transition(1L, OrderStatus.CANCELLED));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("订单状态不能从已完成变更为已取消", exception.getMessage());
        verify(orderMapper, never()).updateById(any(Order.class));
        verifyNoInteractions(productMapper);
    }

    @Test
    @DisplayName("订单乐观锁冲突时返回 409")
    void should_throwConflict_when_orderVersionConflict() {
        when(orderMapper.selectById(1L)).thenReturn(order(1L, OrderStatus.CREATED));
        when(orderMapper.updateById(any(Order.class))).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> orderService.transition(1L, OrderStatus.PAID));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("订单已被他人修改，请刷新后重试", exception.getMessage());
        verifyNoInteractions(productMapper);
    }

    @Test
    @DisplayName("取消时库存恢复失败则不更新订单状态")
    void should_throwConflict_when_stockRestoreFails() {
        when(orderMapper.selectById(1L)).thenReturn(order(1L, OrderStatus.CREATED));
        when(orderMapper.selectByOrderNoForUpdate("SO202611240001"))
            .thenReturn(order(1L, OrderStatus.CREATED));
        when(aftersaleTicketMapper.findBlockingIdByOrderNoForUpdate("SO202611240001"))
            .thenReturn(null);
        when(productMapper.restoreStock(1L, 2)).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> orderService.transition(1L, OrderStatus.CANCELLED));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("库存恢复失败", exception.getMessage());
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    private Order order(Long id, OrderStatus status) {
        Order order = new Order();
        order.setId(id);
        order.setOrderNo("SO202611240001");
        order.setUserId(10001L);
        order.setProductId(1L);
        order.setProductNameSnapshot("无线蓝牙耳机 Pro");
        order.setUnitPriceCents(19900L);
        order.setQuantity(2);
        order.setTotalAmountCents(39800L);
        order.setStatus(status);
        order.setVersion(0);
        order.setReceiverName("郭克");
        order.setReceiverPhone("13800138000");
        order.setShippingAddress("北京市朝阳区测试路 1 号");
        order.setCreatedAt(LocalDateTime.now().minusHours(1));
        if (status == OrderStatus.PAID || status == OrderStatus.SHIPPED
            || status == OrderStatus.DELIVERED) {
            order.setPaidAt(LocalDateTime.now().minusMinutes(30));
        }
        if (status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERED) {
            order.setShippedAt(LocalDateTime.now().minusMinutes(10));
        }
        if (status == OrderStatus.DELIVERED) {
            order.setDeliveredAt(LocalDateTime.now().minusMinutes(1));
        }
        return order;
    }
}
