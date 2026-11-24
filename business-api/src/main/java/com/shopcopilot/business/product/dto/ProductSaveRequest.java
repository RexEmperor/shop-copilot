package com.shopcopilot.business.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "商品创建和修改入参")
public record ProductSaveRequest(
    @Schema(description = "商品名称", example = "无线蓝牙耳机 Pro")
    @NotBlank(message = "商品名称不能为空")
    @Size(max = 128, message = "商品名称不能超过128个字符")
    String name,

    @Schema(description = "商品分类", example = "数码")
    @NotBlank(message = "商品分类不能为空")
    @Size(max = 64, message = "商品分类不能超过64个字符")
    String category,

    @Schema(description = "商品描述")
    @NotBlank(message = "商品描述不能为空")
    String description,

    @Schema(description = "商品价格，单位分", example = "19900")
    @NotNull(message = "商品价格不能为空")
    @Min(value = 0, message = "商品价格不能小于0")
    Long priceCents,

    @Schema(description = "可售库存", example = "200")
    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能小于0")
    Integer stock
) {
}
