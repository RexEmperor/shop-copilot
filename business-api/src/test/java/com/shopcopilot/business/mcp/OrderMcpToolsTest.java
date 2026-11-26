package com.shopcopilot.business.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shopcopilot.business.mcp.McpModels.McpOrderItem;
import com.shopcopilot.business.mcp.McpModels.McpPageData;
import com.shopcopilot.business.order.service.OrderService;
import com.shopcopilot.business.order.vo.OrderVO;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderMcpToolsTest {

    @Mock
    private OrderService orderService;

    private OrderMcpTools tools;

    @BeforeEach
    void setUp() {
        tools = new OrderMcpTools(orderService);
    }

    @Test
    @DisplayName("list_orders 拦截非法状态码，get_order_detail 返回完整金额格式化结果")
    void should_validateOrderStatus_andFormatOrderDetail() {
        McpToolResponse<McpPageData<McpOrderItem>> bad = tools.listOrders(null, 9, 1, 10);
        assertFalse(bad.success());
        assertEquals(400, bad.code());
        verifyNoInteractions(orderService);

        LocalDateTime now = LocalDateTime.of(2026, 11, 26, 10, 0, 0);
        OrderVO order = new OrderVO(
            2L, "SO202611240002", 10001L, 2L, "智能手表 S2",
            89900L, 1, 89900L, 1, "已支付", 0,
            "郭克", "13800000001", "北京市朝阳区示例路1号",
            now, now, null, null, null, now);
        when(orderService.getById(2L)).thenReturn(order);

        McpToolResponse<McpOrderItem> detail = tools.getOrderDetail(2L);
        assertTrue(detail.success());
        assertEquals("899.00", detail.data().unitPriceYuan());
        assertEquals("899.00", detail.data().totalAmountYuan());
        assertEquals("已支付", detail.data().statusText());
    }
}
