package com.shopcopilot.business.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OrderCreateRequest(
    @NotNull(message = "商品ID不能为空")
    @Min(value = 1, message = "商品ID必须大于0")
    Long productId,
    @NotNull(message = "购买数量不能为空")
    @Min(value = 1, message = "购买数量必须大于0")
    Integer quantity,
    @NotBlank(message = "收货人姓名不能为空")
    @Size(max = 64, message = "收货人姓名最长64个字符")
    String receiverName,
    @NotBlank(message = "收货人手机号不能为空")
    @Size(max = 32, message = "收货人手机号最长32个字符")
    String receiverPhone,
    @NotBlank(message = "收货地址不能为空")
    @Size(max = 255, message = "收货地址最长255个字符")
    String shippingAddress
) {
}
