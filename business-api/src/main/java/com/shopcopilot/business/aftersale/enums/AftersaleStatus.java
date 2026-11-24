package com.shopcopilot.business.aftersale.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum AftersaleStatus {

    PENDING(0, "待审核"),
    REFUNDING(1, "退款中"),
    REFUNDED(2, "已退款"),
    REJECTED(3, "已拒绝");

    @EnumValue
    private final int code;

    private final String text;

    AftersaleStatus(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public int getCode() {
        return code;
    }

    public String getText() {
        return text;
    }

    public static AftersaleStatus fromCode(int code) {
        return switch (code) {
            case 0 -> PENDING;
            case 1 -> REFUNDING;
            case 2 -> REFUNDED;
            case 3 -> REJECTED;
            default -> throw new IllegalArgumentException("未知售后状态: " + code);
        };
    }
}
