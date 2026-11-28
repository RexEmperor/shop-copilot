package com.shopcopilot.business.mq;

import com.shopcopilot.business.order.entity.Order;
import com.shopcopilot.business.order.enums.OrderStatus;
import com.shopcopilot.business.product.entity.Product;
import java.time.Instant;
import java.util.UUID;

/**
 * 业务领域异步事件定义（通过 RocketMQ 解耦推送至 agent-runtime）。
 */
public final class DomainEvents {

    private DomainEvents() {
    }

    public record OrderStatusChangedEvent(
        String eventId,
        String orderNo,
        Long userId,
        Long productId,
        String productName,
        int fromStatus,
        int toStatus,
        String toStatusText,
        long totalAmountCents,
        String occurredAt
    ) {
        public static OrderStatusChangedEvent of(Order order, OrderStatus fromStatus, OrderStatus toStatus) {
            return new OrderStatusChangedEvent(
                "evt-ord-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                order.getOrderNo(),
                order.getUserId(),
                order.getProductId(),
                order.getProductNameSnapshot(),
                fromStatus.getCode(),
                toStatus.getCode(),
                toStatus.getDescription(),
                order.getTotalAmountCents(),
                Instant.now().toString()
            );
        }
    }

    public record ProductChangedEvent(
        String eventId,
        Long productId,
        String action,
        String name,
        String category,
        String description,
        long priceCents,
        int stock,
        int status,
        String occurredAt
    ) {
        public static ProductChangedEvent of(Product product, String action) {
            return new ProductChangedEvent(
                "evt-prd-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                product.getId(),
                action,
                product.getName(),
                product.getCategory(),
                product.getDescription(),
                product.getPriceCents(),
                product.getStock(),
                product.getStatus().getCode(),
                Instant.now().toString()
            );
        }
    }
}
