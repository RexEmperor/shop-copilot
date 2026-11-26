package com.shopcopilot.business.mcp;

import com.shopcopilot.business.aftersale.dto.AftersaleCreateRequest;
import com.shopcopilot.business.aftersale.query.AftersaleQuery;
import com.shopcopilot.business.aftersale.service.AftersaleService;
import com.shopcopilot.business.aftersale.vo.AftersaleVO;
import com.shopcopilot.business.common.BusinessException;
import com.shopcopilot.business.common.PageResult;
import com.shopcopilot.business.mcp.McpModels.McpAftersaleItem;
import com.shopcopilot.business.mcp.McpModels.McpPageData;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 售后领域 MCP 工具集（仅依赖售后领域服务）。
 */
@Component
public class AftersaleMcpTools {

    private final AftersaleService aftersaleService;

    public AftersaleMcpTools(AftersaleService aftersaleService) {
        this.aftersaleService = aftersaleService;
    }

    @Tool(name = "create_aftersale", description = "为指定业务订单号发起售后/退款申请。退款金额由系统按订单总价自动计算，不支持手动指定金额。")
    public McpToolResponse<McpAftersaleItem> createAftersale(
            @ToolParam(description = "业务订单号，必填，例如 SO202611240002") String orderNo,
            @ToolParam(description = "售后类型：0-仅退款（已支付/已发货/已完成订单可申请），1-退货退款（仅已发货/已完成订单可申请）") Integer type,
            @ToolParam(description = "售后原因，必填，不超过500字") String reason) {
        return McpToolSupport.executeSafely("create_aftersale", () -> {
            if (!StringUtils.hasText(orderNo) || orderNo.trim().length() > 32) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "订单号不能为空且不能超过32个字符");
            }
            if (type == null || (type != 0 && type != 1)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "售后类型只能为 0（仅退款）或 1（退货退款）");
            }
            if (!StringUtils.hasText(reason) || reason.trim().length() > 500) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "售后原因不能为空且不能超过500个字符");
            }
            Long ticketId = aftersaleService.create(
                new AftersaleCreateRequest(orderNo.trim(), type, reason.trim()));
            return McpAftersaleItem.from(aftersaleService.getById(ticketId));
        });
    }

    @Tool(name = "list_aftersales", description = "查询售后工单列表与审核/退款进度，支持按业务订单号或售后状态过滤。")
    public McpToolResponse<McpPageData<McpAftersaleItem>> listAftersales(
            @ToolParam(required = false, description = "业务订单号，可为空") String orderNo,
            @ToolParam(required = false, description = "售后状态：0-待审核 1-退款中 2-已退款 3-已拒绝，可为空") Integer status,
            @ToolParam(required = false, description = "页码，默认 1") Integer page,
            @ToolParam(required = false, description = "每页数量，默认 10，最大 20") Integer size) {
        return McpToolSupport.executeSafely("list_aftersales", () -> {
            McpToolSupport.validateOptionalRange(status, 0, 3, "售后状态");
            McpToolSupport.validateOptionalOrderNo(orderNo);
            AftersaleQuery query = new AftersaleQuery();
            query.setOrderNo(orderNo);
            query.setStatus(status);
            query.setPage(McpToolSupport.normalizePage(page));
            query.setSize(McpToolSupport.normalizeSize(size));

            PageResult<AftersaleVO> result = aftersaleService.page(query);
            List<McpAftersaleItem> items = result.records().stream().map(McpAftersaleItem::from).toList();
            return McpPageData.from(result, items);
        });
    }
}
