package com.shopcopilot.business.aftersale.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "创建售后入参")
public record AftersaleCreateRequest(
    @Schema(description = "业务订单号", example = "SO202611240002")
    @NotBlank(message = "订单号不能为空")
    @Size(max = 32, message = "订单号不能超过32个字符")
    String orderNo,

    @Schema(description = "售后类型：0-仅退款，1-退货退款", example = "0")
    @NotNull(message = "售后类型不能为空")
    @Min(value = 0, message = "售后类型只能为0或1")
    @Max(value = 1, message = "售后类型只能为0或1")
    Integer type,

    @Schema(description = "售后原因", example = "商品与描述不符")
    @NotBlank(message = "售后原因不能为空")
    @Size(max = 500, message = "售后原因不能超过500个字符")
    String reason
) {
}
