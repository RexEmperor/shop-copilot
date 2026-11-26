package com.shopcopilot.business.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shopcopilot.business.common.BusinessException;
import com.shopcopilot.business.common.PageResult;
import com.shopcopilot.business.mcp.McpModels.McpCategoryItem;
import com.shopcopilot.business.mcp.McpModels.McpProductItem;
import com.shopcopilot.business.mcp.McpModels.McpProductSearchData;
import com.shopcopilot.business.product.query.ProductQuery;
import com.shopcopilot.business.product.service.ProductService;
import com.shopcopilot.business.product.vo.ProductCategoryCount;
import com.shopcopilot.business.product.vo.ProductVO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class ProductMcpToolsTest {

    @Mock
    private ProductService productService;

    private ProductMcpTools tools;

    @BeforeEach
    void setUp() {
        tools = new ProductMcpTools(productService);
    }

    @Test
    @DisplayName("list_categories 返回在售分类列表与商品数")
    void should_listOnSaleCategories() {
        when(productService.listOnSaleCategories()).thenReturn(List.of(
            new ProductCategoryCount("数码", 4L),
            new ProductCategoryCount("服饰", 2L)
        ));

        McpToolResponse<List<McpCategoryItem>> resp = tools.listCategories();

        assertTrue(resp.success());
        assertEquals(2, resp.data().size());
        assertEquals("数码", resp.data().get(0).category());
        assertEquals(4L, resp.data().get(0).productCount());
    }

    @Test
    @DisplayName("search_products 有结果时不额外查类目，并限制分页上限为 20 且格式化价格")
    void should_clampPagination_andFormatYuan_when_searchingProducts() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 26, 10, 0, 0);
        ProductVO vo = new ProductVO(
            1L, "无线蓝牙耳机 Pro", "数码", "降噪耳机", 19900L, 120, 1, "上架中", 0, now, now);
        when(productService.page(any(ProductQuery.class)))
            .thenReturn(new PageResult<>(List.of(vo), 1, 1, 20));

        McpToolResponse<McpProductSearchData> resp = tools.searchProducts("耳机", "数码", 0, 99);

        assertTrue(resp.success());
        assertEquals(200, resp.code());
        assertEquals("199.00", resp.data().records().get(0).priceYuan());
        assertEquals(19900L, resp.data().records().get(0).priceCents());
        assertNull(resp.data().availableCategories());
        verify(productService, never()).listOnSaleCategories();

        ArgumentCaptor<ProductQuery> captor = ArgumentCaptor.forClass(ProductQuery.class);
        verify(productService).page(captor.capture());
        assertEquals(1, captor.getValue().getPage());
        assertEquals(20, captor.getValue().getSize());
        assertEquals(1, captor.getValue().getStatus());
    }

    @Test
    @DisplayName("search_products 零结果时附带 availableCategories 供模型纠偏")
    void should_includeAvailableCategories_when_searchReturnsEmpty() {
        when(productService.page(any(ProductQuery.class)))
            .thenReturn(new PageResult<>(List.of(), 0, 1, 10));
        when(productService.listOnSaleCategories()).thenReturn(List.of(
            new ProductCategoryCount("数码", 4L),
            new ProductCategoryCount("食品", 2L)
        ));

        McpToolResponse<McpProductSearchData> resp = tools.searchProducts("数码", null, 1, 10);

        assertTrue(resp.success());
        assertEquals(0, resp.data().total());
        assertEquals(List.of("数码", "食品"), resp.data().availableCategories());
    }

    @Test
    @DisplayName("get_product_detail 遇到非法 ID、下架 404 或未知异常均返回结构化错误")
    void should_returnStructuredFailure_when_invalidOrOffSaleOrUnexpected() {
        McpToolResponse<McpProductItem> bad = tools.getProductDetail(0L);
        assertFalse(bad.success());
        assertEquals(400, bad.code());
        verifyNoInteractions(productService);

        when(productService.getById(10L))
            .thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "商品已下架"));
        McpToolResponse<McpProductItem> offSale = tools.getProductDetail(10L);
        assertFalse(offSale.success());
        assertEquals(404, offSale.code());
        assertEquals("商品已下架", offSale.message());
        assertNull(offSale.data());

        when(productService.getById(1L)).thenThrow(new RuntimeException("DB reset"));
        McpToolResponse<McpProductItem> error = tools.getProductDetail(1L);
        assertFalse(error.success());
        assertEquals(500, error.code());
        assertTrue(error.message().contains("转人工客服"));
    }
}
