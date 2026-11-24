package com.shopcopilot.business.product.query;

import com.shopcopilot.business.common.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Schema(description = "商品分页查询条件")
public class ProductQuery extends PageQuery {

    @Schema(description = "商品名称关键词")
    private String keyword;

    @Schema(description = "商品分类")
    private String category;

    @Schema(description = "商品状态：0-下架，1-上架；默认只查上架商品")
    @Min(0)
    @Max(1)
    private Integer status = 1;

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
