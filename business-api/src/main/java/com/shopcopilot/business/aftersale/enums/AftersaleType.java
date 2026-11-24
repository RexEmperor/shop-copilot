package com.shopcopilot.business.aftersale.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum AftersaleType {

    REFUND_ONLY(0, "仅退款"),
    RETURN_REFUND(1, "退货退款");

    @EnumValue
    private final int code;

    private final String text;

    AftersaleType(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public int getCode() {
        return code;
    }

    public String getText() {
        return text;
    }

    public static AftersaleType fromCode(int code) {
        return switch (code) {
            case 0 -> REFUND_ONLY;
            case 1 -> RETURN_REFUND;
            default -> throw new IllegalArgumentException("未知售后类型: " + code);
        };
    }
}
