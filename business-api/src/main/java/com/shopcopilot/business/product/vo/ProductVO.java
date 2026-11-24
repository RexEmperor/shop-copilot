package com.shopcopilot.business.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "商品返回对象")
public record ProductVO(
    @Schema(description = "商品ID")
    Long id,

    @Schema(description = "商品名称")
    String name,

    @Schema(description = "商品分类")
    String category,

    @Schema(description = "商品描述")
    String description,

    @Schema(description = "商品价格，单位分")
    Long priceCents,

    @Schema(description = "可售库存")
    Integer stock,

    @Schema(description = "商品状态：0-下架，1-上架")
    Integer status,

    @Schema(description = "商品状态描述")
    String statusText,

    @Schema(description = "乐观锁版本号")
    Integer version,

    @Schema(description = "创建时间")
    LocalDateTime createdAt,

    @Schema(description = "更新时间")
    LocalDateTime updatedAt
) {
}
