package com.shopcopilot.business.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shopcopilot.business.common.BusinessException;
import com.shopcopilot.business.common.PageResult;
import com.shopcopilot.business.mcp.McpModels.McpPageData;
import com.shopcopilot.business.mcp.McpModels.McpProductItem;
import com.shopcopilot.business.product.query.ProductQuery;
import com.shopcopilot.business.product.service.ProductService;
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
    @DisplayName("search_products 自动限制分页上限为 20 并格式化价格为元")
    void should_clampPagination_andFormatYuan_when_searchingProducts() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 26, 10, 0, 0);
        ProductVO vo = new ProductVO(
            1L, "无线蓝牙耳机 Pro", "数码", "降噪耳机", 19900L, 120, 1, "上架中", 0, now, now);
        when(productService.page(any(ProductQuery.class)))
            .thenReturn(new PageResult<>(List.of(vo), 1, 1, 20));

        McpToolResponse<McpPageData<McpProductItem>> resp = tools.searchProducts("耳机", "数码", 0, 99);

        assertTrue(resp.success());
        assertEquals(200, resp.code());
        assertEquals("199.00", resp.data().records().get(0).priceYuan());
        assertEquals(19900L, resp.data().records().get(0).priceCents());

        ArgumentCaptor<ProductQuery> captor = ArgumentCaptor.forClass(ProductQuery.class);
        verify(productService).page(captor.capture());
        assertEquals(1, captor.getValue().getPage());
        assertEquals(20, captor.getValue().getSize());
        assertEquals(1, captor.getValue().getStatus());
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
