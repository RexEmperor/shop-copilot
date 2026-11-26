package com.shopcopilot.business.mcp;

import com.shopcopilot.business.common.PageResult;
import com.shopcopilot.business.mcp.McpModels.McpOrderItem;
import com.shopcopilot.business.mcp.McpModels.McpPageData;
import com.shopcopilot.business.order.query.OrderQuery;
import com.shopcopilot.business.order.service.OrderService;
import com.shopcopilot.business.order.vo.OrderVO;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 订单与履约领域 MCP 工具集（仅依赖订单领域服务）。
 */
@Component
public class OrderMcpTools {

    private final OrderService orderService;

    public OrderMcpTools(OrderService orderService) {
        this.orderService = orderService;
    }

    @Tool(name = "list_orders", description = "查询当前用户的订单列表，支持按业务订单号或订单状态过滤，并返回履约时间线。")
    public McpToolResponse<McpPageData<McpOrderItem>> listOrders(
            @ToolParam(required = false, description = "业务订单号，例如 SO202611240002，可为空") String orderNo,
            @ToolParam(required = false, description = "订单状态：0-待支付 1-已支付 2-已发货 3-已完成 4-已取消，可为空") Integer status,
            @ToolParam(required = false, description = "页码，默认 1") Integer page,
            @ToolParam(required = false, description = "每页数量，默认 10，最大 20") Integer size) {
        return McpToolSupport.executeSafely("list_orders", () -> {
            McpToolSupport.validateOptionalRange(status, 0, 4, "订单状态");
            McpToolSupport.validateOptionalOrderNo(orderNo);
            OrderQuery query = new OrderQuery();
            query.setOrderNo(orderNo);
            query.setStatus(status);
            query.setPage(McpToolSupport.normalizePage(page));
            query.setSize(McpToolSupport.normalizeSize(size));

            PageResult<OrderVO> result = orderService.page(query);
            List<McpOrderItem> items = result.records().stream().map(McpOrderItem::from).toList();
            return McpPageData.from(result, items);
        });
    }

    @Tool(name = "get_order_detail", description = "按订单主键ID查询单个订单的完整详情、收货信息及支付/发货/收货状态时间线。")
    public McpToolResponse<McpOrderItem> getOrderDetail(
            @ToolParam(description = "订单主键ID，正整数") Long orderId) {
        return McpToolSupport.executeSafely("get_order_detail", () -> {
            McpToolSupport.requirePositiveId(orderId, "订单ID");
            return McpOrderItem.from(orderService.getById(orderId));
        });
    }
}
