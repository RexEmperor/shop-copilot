package com.shopcopilot.business.product.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum ProductStatus {

    OFF_SALE(0, "已下架"),
    ON_SALE(1, "上架中");

    @EnumValue
    private final int code;

    private final String text;

    ProductStatus(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public int getCode() {
        return code;
    }

    public String getText() {
        return text;
    }

    public static ProductStatus fromCode(int code) {
        return switch (code) {
            case 0 -> OFF_SALE;
            case 1 -> ON_SALE;
            default -> throw new IllegalArgumentException("未知商品状态: " + code);
        };
    }
}
