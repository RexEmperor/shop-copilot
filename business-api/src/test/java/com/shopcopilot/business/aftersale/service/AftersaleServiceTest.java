package com.shopcopilot.business.aftersale.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shopcopilot.business.aftersale.dto.AftersaleCreateRequest;
import com.shopcopilot.business.aftersale.entity.AftersaleTicket;
import com.shopcopilot.business.aftersale.enums.AftersaleStatus;
import com.shopcopilot.business.aftersale.enums.AftersaleType;
import com.shopcopilot.business.aftersale.mapper.AftersaleTicketMapper;
import com.shopcopilot.business.aftersale.service.impl.AftersaleServiceImpl;
import com.shopcopilot.business.aftersale.state.AftersaleStateMachine;
import com.shopcopilot.business.aftersale.vo.AftersaleVO;
import com.shopcopilot.business.common.BusinessException;
import com.shopcopilot.business.order.entity.Order;
import com.shopcopilot.business.order.enums.OrderStatus;
import com.shopcopilot.business.order.mapper.OrderMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AftersaleServiceTest {

    private static final String ORDER_NO = "SO202611240002";

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private AftersaleTicketMapper aftersaleMapper;

    private AftersaleService aftersaleService;

    @BeforeEach
    void setUp() {
        aftersaleService = new AftersaleServiceImpl(
            orderMapper, aftersaleMapper, new AftersaleStateMachine());
    }

    @Test
    @DisplayName("已支付订单可发起仅退款，金额从订单派生")
    void should_createRefundOnlyTicket_when_orderPaid() {
        when(orderMapper.selectByOrderNoForUpdate(ORDER_NO)).thenReturn(order(OrderStatus.PAID));
        when(aftersaleMapper.countActiveByOrderNo(ORDER_NO)).thenReturn(0L);
        when(aftersaleMapper.insert(any(AftersaleTicket.class))).thenAnswer(invocation -> {
            AftersaleTicket ticket = invocation.getArgument(0);
            ticket.setId(100L);
            return 1;
        });

        Long id = aftersaleService.create(new AftersaleCreateRequest(ORDER_NO, 0, "商品与描述不符"));

        assertEquals(100L, id);
        ArgumentCaptor<AftersaleTicket> captor = ArgumentCaptor.forClass(AftersaleTicket.class);
        verify(aftersaleMapper).insert(captor.capture());
        AftersaleTicket saved = captor.getValue();
        assertNotNull(saved.getTicketNo());
        assertEquals(ORDER_NO, saved.getOrderNo());
        assertEquals(10001L, saved.getUserId());
        assertEquals(0, saved.getType().getCode());
        assertEquals(AftersaleStatus.PENDING, saved.getStatus());
        assertEquals(39800L, saved.getRefundCents());
        assertEquals("商品与描述不符", saved.getReason());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"SHIPPED", "DELIVERED"})
    @DisplayName("已发货或已完成订单可发起退货退款")
    void should_createReturnRefundTicket_when_orderShippedOrDelivered(OrderStatus status) {
        when(orderMapper.selectByOrderNoForUpdate(ORDER_NO)).thenReturn(order(status));
        when(aftersaleMapper.countActiveByOrderNo(ORDER_NO)).thenReturn(0L);
        when(aftersaleMapper.insert(any(AftersaleTicket.class))).thenReturn(1);

        aftersaleService.create(new AftersaleCreateRequest(ORDER_NO, 1, "尺码不合适"));

        ArgumentCaptor<AftersaleTicket> captor = ArgumentCaptor.forClass(AftersaleTicket.class);
        verify(aftersaleMapper).insert(captor.capture());
        assertEquals(1, captor.getValue().getType().getCode());
    }

    @Test
    @DisplayName("订单不存在时返回 404 且不创建工单")
    void should_throwNotFound_when_orderMissing() {
        when(orderMapper.selectByOrderNoForUpdate(ORDER_NO)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> aftersaleService.create(new AftersaleCreateRequest(ORDER_NO, 0, "原因")));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("订单不存在", exception.getMessage());
        verifyNoInteractions(aftersaleMapper);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CREATED", "CANCELLED"})
    @DisplayName("待支付或已取消订单不能发起售后")
    void should_throwConflict_when_orderStatusNotEligible(OrderStatus status) {
        when(orderMapper.selectByOrderNoForUpdate(ORDER_NO)).thenReturn(order(status));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> aftersaleService.create(new AftersaleCreateRequest(ORDER_NO, 0, "原因")));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("当前订单状态不允许发起售后", exception.getMessage());
        verifyNoInteractions(aftersaleMapper);
    }

    @Test
    @DisplayName("已支付订单不能发起退货退款")
    void should_throwConflict_when_returnRefundBeforeShipment() {
        when(orderMapper.selectByOrderNoForUpdate(ORDER_NO)).thenReturn(order(OrderStatus.PAID));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> aftersaleService.create(new AftersaleCreateRequest(ORDER_NO, 1, "原因")));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("当前订单状态不允许退货退款", exception.getMessage());
        verifyNoInteractions(aftersaleMapper);
    }

    @Test
    @DisplayName("订单已有进行中售后时拒绝重复创建")
    void should_throwConflict_when_activeAftersaleExists() {
        when(orderMapper.selectByOrderNoForUpdate(ORDER_NO)).thenReturn(order(OrderStatus.PAID));
        when(aftersaleMapper.countActiveByOrderNo(ORDER_NO)).thenReturn(1L);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> aftersaleService.create(new AftersaleCreateRequest(ORDER_NO, 0, "原因")));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("该订单已有进行中的售后工单", exception.getMessage());
        verify(aftersaleMapper, never()).insert(any(AftersaleTicket.class));
    }

    @Test
    @DisplayName("审核通过后进入退款中并写入审核时间")
    void should_approvePendingTicket() {
        when(aftersaleMapper.selectById(1L)).thenReturn(ticket(1L, AftersaleStatus.PENDING));
        when(aftersaleMapper.approve(eq(1L), any(LocalDateTime.class))).thenReturn(1);

        AftersaleVO result = aftersaleService.approve(1L);

        assertEquals(1, result.status());
        assertEquals("退款中", result.statusText());
        assertNotNull(result.reviewedAt());
        assertNull(result.refundedAt());
    }

    @Test
    @DisplayName("审核拒绝后写入备注和审核时间")
    void should_rejectPendingTicket() {
        when(aftersaleMapper.selectById(1L)).thenReturn(ticket(1L, AftersaleStatus.PENDING));
        when(aftersaleMapper.reject(eq(1L), eq("证据不足"), any(LocalDateTime.class))).thenReturn(1);

        AftersaleVO result = aftersaleService.reject(1L, "证据不足");

        assertEquals(3, result.status());
        assertEquals("已拒绝", result.statusText());
        assertEquals("证据不足", result.reviewRemark());
        assertNotNull(result.reviewedAt());
        assertNull(result.refundedAt());
    }

    @Test
    @DisplayName("退款中工单确认退款后写入退款时间")
    void should_completeRefund_when_ticketRefunding() {
        when(aftersaleMapper.selectById(1L)).thenReturn(ticket(1L, AftersaleStatus.REFUNDING));
        when(aftersaleMapper.completeRefund(eq(1L), any(LocalDateTime.class))).thenReturn(1);

        AftersaleVO result = aftersaleService.completeRefund(1L);

        assertEquals(2, result.status());
        assertEquals("已退款", result.statusText());
        assertNotNull(result.reviewedAt());
        assertNotNull(result.refundedAt());
    }

    @Test
    @DisplayName("售后工单不存在时返回 404")
    void should_throwNotFound_when_ticketMissing() {
        when(aftersaleMapper.selectById(999L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> aftersaleService.approve(999L));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("售后工单不存在", exception.getMessage());
        verify(aftersaleMapper, never()).approve(any(), any());
    }

    @Test
    @DisplayName("退款中工单不能再次审核")
    void should_throwConflict_when_ticketAlreadyRefunding() {
        when(aftersaleMapper.selectById(1L)).thenReturn(ticket(1L, AftersaleStatus.REFUNDING));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> aftersaleService.approve(1L));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("售后状态不能从退款中变更为退款中", exception.getMessage());
        verify(aftersaleMapper, never()).approve(any(), any());
    }

    @Test
    @DisplayName("已退款工单不能再次确认退款")
    void should_throwConflict_when_ticketAlreadyRefunded() {
        when(aftersaleMapper.selectById(1L)).thenReturn(ticket(1L, AftersaleStatus.REFUNDED));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> aftersaleService.completeRefund(1L));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("售后状态不能从已退款变更为已退款", exception.getMessage());
        verify(aftersaleMapper, never()).completeRefund(any(), any());
    }

    @Test
    @DisplayName("并发审核更新失败时返回 409")
    void should_throwConflict_when_conditionalApproveUpdatesNothing() {
        when(aftersaleMapper.selectById(1L)).thenReturn(ticket(1L, AftersaleStatus.PENDING));
        when(aftersaleMapper.approve(eq(1L), any(LocalDateTime.class))).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> aftersaleService.approve(1L));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("售后状态不允许流转", exception.getMessage());
    }

    @Test
    @DisplayName("并发拒绝更新失败时返回 409")
    void should_throwConflict_when_conditionalRejectUpdatesNothing() {
        when(aftersaleMapper.selectById(1L)).thenReturn(ticket(1L, AftersaleStatus.PENDING));
        when(aftersaleMapper.reject(eq(1L), eq("证据不足"), any(LocalDateTime.class))).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> aftersaleService.reject(1L, "证据不足"));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("售后状态不允许流转", exception.getMessage());
    }

    @Test
    @DisplayName("并发确认退款更新失败时返回 409")
    void should_throwConflict_when_conditionalRefundUpdatesNothing() {
        when(aftersaleMapper.selectById(1L)).thenReturn(ticket(1L, AftersaleStatus.REFUNDING));
        when(aftersaleMapper.completeRefund(eq(1L), any(LocalDateTime.class))).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> aftersaleService.completeRefund(1L));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("售后状态不允许流转", exception.getMessage());
    }

    private Order order(OrderStatus status) {
        Order order = new Order();
        order.setId(2L);
        order.setOrderNo(ORDER_NO);
        order.setUserId(10001L);
        order.setProductId(2L);
        order.setProductNameSnapshot("智能手表 S2");
        order.setUnitPriceCents(39900L);
        order.setQuantity(1);
        order.setTotalAmountCents(39800L);
        order.setStatus(status);
        order.setVersion(0);
        order.setCreatedAt(LocalDateTime.now().minusDays(1));
        order.setUpdatedAt(LocalDateTime.now());
        return order;
    }

    private AftersaleTicket ticket(Long id, AftersaleStatus status) {
        AftersaleTicket ticket = new AftersaleTicket();
        ticket.setId(id);
        ticket.setTicketNo("AS202611250001");
        ticket.setOrderNo(ORDER_NO);
        ticket.setUserId(10001L);
        ticket.setType(AftersaleType.REFUND_ONLY);
        ticket.setStatus(status);
        ticket.setRefundCents(39800L);
        ticket.setReason("商品与描述不符");
        ticket.setCreatedAt(LocalDateTime.now().minusHours(1));
        ticket.setUpdatedAt(LocalDateTime.now());
        if (status == AftersaleStatus.REFUNDING || status == AftersaleStatus.REFUNDED) {
            ticket.setReviewedAt(LocalDateTime.now().minusMinutes(30));
        }
        if (status == AftersaleStatus.REFUNDED) {
            ticket.setRefundedAt(LocalDateTime.now().minusMinutes(1));
        }
        return ticket;
    }
}
