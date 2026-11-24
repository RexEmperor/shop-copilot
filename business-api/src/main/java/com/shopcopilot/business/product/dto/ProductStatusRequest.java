package com.shopcopilot.business.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "商品上下架入参")
public record ProductStatusRequest(
    @Schema(description = "商品状态：0-下架，1-上架", example = "1")
    @NotNull(message = "商品状态不能为空")
    @Min(value = 0, message = "商品状态只能为0或1")
    @Max(value = 1, message = "商品状态只能为0或1")
    Integer status
) {
}
