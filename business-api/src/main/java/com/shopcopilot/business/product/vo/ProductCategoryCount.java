package com.shopcopilot.business.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "在售商品分类统计")
public record ProductCategoryCount(
    @Schema(description = "商品分类名称")
    String category,

    @Schema(description = "该分类当前在售商品数量")
    long productCount
) {
}
