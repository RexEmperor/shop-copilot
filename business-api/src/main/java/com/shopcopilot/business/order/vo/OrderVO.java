package com.shopcopilot.business.order.vo;

import java.time.LocalDateTime;

public record OrderVO(
    Long id,
    String orderNo,
    Long userId,
    Long productId,
    String productNameSnapshot,
    Long unitPriceCents,
    Integer quantity,
    Long totalAmountCents,
    Integer status,
    String statusText,
    Integer version,
    String receiverName,
    String receiverPhone,
    String shippingAddress,
    LocalDateTime createdAt,
    LocalDateTime paidAt,
    LocalDateTime shippedAt,
    LocalDateTime deliveredAt,
    LocalDateTime cancelledAt,
    LocalDateTime updatedAt
) {
}
