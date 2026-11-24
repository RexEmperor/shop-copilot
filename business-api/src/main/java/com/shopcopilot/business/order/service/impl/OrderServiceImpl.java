package com.shopcopilot.business.order.service.impl;

import com.shopcopilot.business.aftersale.mapper.AftersaleTicketMapper;
import com.shopcopilot.business.common.BusinessException;
import com.shopcopilot.business.order.dto.OrderCreateRequest;
import com.shopcopilot.business.order.entity.Order;
import com.shopcopilot.business.order.enums.OrderStatus;
import com.shopcopilot.business.order.mapper.OrderMapper;
import com.shopcopilot.business.order.service.OrderService;
import com.shopcopilot.business.order.state.OrderStateMachine;
import com.shopcopilot.business.order.vo.OrderVO;
import com.shopcopilot.business.product.entity.Product;
import com.shopcopilot.business.product.enums.ProductStatus;
import com.shopcopilot.business.product.mapper.ProductMapper;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderServiceImpl implements OrderService {

    private static final long FIXED_USER_ID = 10001L;

    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final OrderStateMachine orderStateMachine;
    private final AftersaleTicketMapper aftersaleTicketMapper;

    public OrderServiceImpl(
            ProductMapper productMapper,
            OrderMapper orderMapper,
            OrderStateMachine orderStateMachine,
            AftersaleTicketMapper aftersaleTicketMapper) {
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
        this.orderStateMachine = orderStateMachine;
        this.aftersaleTicketMapper = aftersaleTicketMapper;
    }

    @Override
    @Transactional
    public OrderVO create(OrderCreateRequest request) {
        Product product = productMapper.selectById(request.productId());
        if (product == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        if (product.getStatus() != ProductStatus.ON_SALE) {
            throw new BusinessException(HttpStatus.CONFLICT, "商品已下架");
        }

        long totalAmountCents = calculateTotal(product.getPriceCents(), request.quantity());
        int deductedRows = productMapper.deductStock(product.getId(), request.quantity());
        if (deductedRows == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "商品库存不足或已下架");
        }

        Order order = new Order();
        order.setOrderNo(generateOrderNo());
        order.setUserId(FIXED_USER_ID);
        order.setProductId(product.getId());
        order.setProductNameSnapshot(product.getName());
        order.setUnitPriceCents(product.getPriceCents());
        order.setQuantity(request.quantity());
        order.setTotalAmountCents(totalAmountCents);
        order.setStatus(OrderStatus.CREATED);
        order.setVersion(0);
        LocalDateTime now = LocalDateTime.now();
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        order.setReceiverName(request.receiverName().trim());
        order.setReceiverPhone(request.receiverPhone().trim());
        order.setShippingAddress(request.shippingAddress().trim());

        int insertedRows = orderMapper.insert(order);
        if (insertedRows == 0) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "订单创建失败");
        }
        return toVO(order);
    }

    @Override
    @Transactional
    public OrderVO transition(Long id, OrderStatus targetStatus) {
        Order order = requireOrder(id);
        OrderStatus nextStatus = orderStateMachine.transition(order.getStatus(), targetStatus);

        if (nextStatus == OrderStatus.CANCELLED) {
            Order lockedOrder = orderMapper.selectByOrderNoForUpdate(order.getOrderNo());
            if (lockedOrder == null) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "订单不存在");
            }
            if (lockedOrder.getStatus() != order.getStatus()) {
                throw new BusinessException(HttpStatus.CONFLICT, "订单已被他人修改，请刷新后重试");
            }
            if (aftersaleTicketMapper.findBlockingIdByOrderNoForUpdate(order.getOrderNo()) != null) {
                throw new BusinessException(HttpStatus.CONFLICT, "该订单存在售后记录，不能取消");
            }
            int restoredRows = productMapper.restoreStock(order.getProductId(), order.getQuantity());
            if (restoredRows == 0) {
                throw new BusinessException(HttpStatus.CONFLICT, "库存恢复失败");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        order.setStatus(nextStatus);
        order.setUpdatedAt(now);
        switch (nextStatus) {
            case PAID -> order.setPaidAt(now);
            case SHIPPED -> order.setShippedAt(now);
            case DELIVERED -> order.setDeliveredAt(now);
            case CANCELLED -> order.setCancelledAt(now);
            case CREATED -> throw new BusinessException(HttpStatus.CONFLICT, "订单状态不能变更为待支付");
        }

        int updatedRows = orderMapper.updateById(order);
        if (updatedRows == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "订单已被他人修改，请刷新后重试");
        }
        return toVO(order);
    }

    private Order requireOrder(Long id) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "订单不存在");
        }
        return order;
    }

    private long calculateTotal(long unitPriceCents, int quantity) {
        try {
            return Math.multiplyExact(unitPriceCents, quantity);
        } catch (ArithmeticException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "订单金额超出范围");
        }
    }

    private String generateOrderNo() {
        return "O" + UUID.randomUUID().toString().replace("-", "").substring(0, 31);
    }

    private OrderVO toVO(Order order) {
        return new OrderVO(
            order.getId(),
            order.getOrderNo(),
            order.getUserId(),
            order.getProductId(),
            order.getProductNameSnapshot(),
            order.getUnitPriceCents(),
            order.getQuantity(),
            order.getTotalAmountCents(),
            order.getStatus().getCode(),
            order.getStatus().getDescription(),
            order.getVersion(),
            order.getReceiverName(),
            order.getReceiverPhone(),
            order.getShippingAddress(),
            order.getCreatedAt(),
            order.getPaidAt(),
            order.getShippedAt(),
            order.getDeliveredAt(),
            order.getCancelledAt(),
            order.getUpdatedAt()
        );
    }
}
