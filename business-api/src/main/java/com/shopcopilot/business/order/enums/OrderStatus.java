package com.shopcopilot.business.order.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum OrderStatus {

    CREATED(0, "待支付"),
    PAID(1, "已支付"),
    SHIPPED(2, "已发货"),
    DELIVERED(3, "已完成"),
    CANCELLED(4, "已取消");

    @EnumValue
    private final int code;

    private final String description;

    OrderStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public boolean isTerminal() {
        return switch (this) {
            case DELIVERED, CANCELLED -> true;
            case CREATED, PAID, SHIPPED -> false;
        };
    }

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case CREATED -> target == PAID || target == CANCELLED;
            case PAID -> target == SHIPPED || target == CANCELLED;
            case SHIPPED -> target == DELIVERED || target == CANCELLED;
            case DELIVERED, CANCELLED -> false;
        };
    }

    public static OrderStatus fromCode(int code) {
        return switch (code) {
            case 0 -> CREATED;
            case 1 -> PAID;
            case 2 -> SHIPPED;
            case 3 -> DELIVERED;
            case 4 -> CANCELLED;
            default -> throw new IllegalArgumentException("未知订单状态: " + code);
        };
    }
}
