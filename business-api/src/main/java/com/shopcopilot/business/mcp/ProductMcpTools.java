package com.shopcopilot.business.mcp;

import com.shopcopilot.business.common.PageResult;
import com.shopcopilot.business.mcp.McpModels.McpPageData;
import com.shopcopilot.business.mcp.McpModels.McpProductItem;
import com.shopcopilot.business.product.enums.ProductStatus;
import com.shopcopilot.business.product.query.ProductQuery;
import com.shopcopilot.business.product.service.ProductService;
import com.shopcopilot.business.product.vo.ProductVO;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 商品与导购领域 MCP 工具集（仅依赖商品领域服务）。
 */
@Component
public class ProductMcpTools {

    private final ProductService productService;

    public ProductMcpTools(ProductService productService) {
        this.productService = productService;
    }

    @Tool(name = "search_products", description = "搜索当前在售商品，支持按商品名称关键词、分类和分页条件查询。")
    public McpToolResponse<McpPageData<McpProductItem>> searchProducts(
            @ToolParam(required = false, description = "商品名称关键词，例如 耳机、咖啡，可为空") String keyword,
            @ToolParam(required = false, description = "商品分类，例如 数码、服饰、食品、家居，可为空") String category,
            @ToolParam(required = false, description = "页码，默认 1") Integer page,
            @ToolParam(required = false, description = "每页数量，默认 10，最大 20") Integer size) {
        return McpToolSupport.executeSafely("search_products", () -> {
            ProductQuery query = new ProductQuery();
            query.setKeyword(keyword);
            query.setCategory(category);
            query.setStatus(ProductStatus.ON_SALE.getCode());
            query.setPage(McpToolSupport.normalizePage(page));
            query.setSize(McpToolSupport.normalizeSize(size));

            PageResult<ProductVO> result = productService.page(query);
            List<McpProductItem> items = result.records().stream().map(McpProductItem::from).toList();
            return McpPageData.from(result, items);
        });
    }

    @Tool(name = "get_product_detail", description = "按商品ID查询单个在售商品的详细信息，包括价格（元/分）、库存和描述。")
    public McpToolResponse<McpProductItem> getProductDetail(
            @ToolParam(description = "商品主键ID，正整数") Long productId) {
        return McpToolSupport.executeSafely("get_product_detail", () -> {
            McpToolSupport.requirePositiveId(productId, "商品ID");
            return McpProductItem.from(productService.getById(productId));
        });
    }
}
