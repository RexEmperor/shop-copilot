package com.shopcopilot.business.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.shopcopilot.business.aftersale.vo.AftersaleVO;
import com.shopcopilot.business.common.PageResult;
import com.shopcopilot.business.order.vo.OrderVO;
import com.shopcopilot.business.product.vo.ProductCategoryCount;
import com.shopcopilot.business.product.vo.ProductVO;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 面向 MCP 调用方（LLM Agent）的精简输出模型，同时附带分值与格式化元字符串，
 * 避免模型自行换算金额出错。
 */
public final class McpModels {

    private McpModels() {
    }

    public record McpPageData<T>(
        List<T> records,
        long total,
        long page,
        long size
    ) {
        public static <S, T> McpPageData<T> from(PageResult<S> source, List<T> mapped) {
            return new McpPageData<>(mapped, source.total(), source.page(), source.size());
        }
    }

    public record McpCategoryItem(
        String category,
        long productCount
    ) {
        public static McpCategoryItem from(ProductCategoryCount source) {
            return new McpCategoryItem(source.category(), source.productCount());
        }
    }

    public record McpProductSearchData(
        List<McpProductItem> records,
        long total,
        long page,
        long size,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<String> availableCategories
    ) {
        public static McpProductSearchData of(
                PageResult<ProductVO> source,
                List<McpProductItem> mapped,
                List<String> availableCategories) {
            return new McpProductSearchData(
                mapped, source.total(), source.page(), source.size(), availableCategories);
        }
    }

    public record McpProductItem(
        Long id,
        String name,
        String category,
        String description,
        Long priceCents,
        String priceYuan,
        Integer stock,
        Integer status,
        String statusText
    ) {
        public static McpProductItem from(ProductVO vo) {
            return new McpProductItem(
                vo.id(),
                vo.name(),
                vo.category(),
                vo.description(),
                vo.priceCents(),
                McpToolResponse.centsToYuan(vo.priceCents()),
                vo.stock(),
                vo.status(),
                vo.statusText()
            );
        }
    }

    public record McpOrderItem(
        Long id,
        String orderNo,
        Long productId,
        String productNameSnapshot,
        Long unitPriceCents,
        String unitPriceYuan,
        Integer quantity,
        Long totalAmountCents,
        String totalAmountYuan,
        Integer status,
        String statusText,
        String receiverName,
        String receiverPhone,
        String shippingAddress,
        LocalDateTime createdAt,
        LocalDateTime paidAt,
        LocalDateTime shippedAt,
        LocalDateTime deliveredAt,
        LocalDateTime cancelledAt
    ) {
        public static McpOrderItem from(OrderVO vo) {
            return new McpOrderItem(
                vo.id(),
                vo.orderNo(),
                vo.productId(),
                vo.productNameSnapshot(),
                vo.unitPriceCents(),
                McpToolResponse.centsToYuan(vo.unitPriceCents()),
                vo.quantity(),
                vo.totalAmountCents(),
                McpToolResponse.centsToYuan(vo.totalAmountCents()),
                vo.status(),
                vo.statusText(),
                vo.receiverName(),
                vo.receiverPhone(),
                vo.shippingAddress(),
                vo.createdAt(),
                vo.paidAt(),
                vo.shippedAt(),
                vo.deliveredAt(),
                vo.cancelledAt()
            );
        }
    }

    public record McpAftersaleItem(
        Long id,
        String ticketNo,
        String orderNo,
        Integer type,
        String typeText,
        Integer status,
        String statusText,
        Long refundCents,
        String refundYuan,
        String reason,
        String reviewRemark,
        LocalDateTime reviewedAt,
        LocalDateTime refundedAt,
        LocalDateTime createdAt
    ) {
        public static McpAftersaleItem from(AftersaleVO vo) {
            String typeText = vo.type() != null && vo.type() == 1 ? "退货退款" : "仅退款";
            return new McpAftersaleItem(
                vo.id(),
                vo.ticketNo(),
                vo.orderNo(),
                vo.type(),
                typeText,
                vo.status(),
                vo.statusText(),
                vo.refundCents(),
                McpToolResponse.centsToYuan(vo.refundCents()),
                vo.reason(),
                vo.reviewRemark(),
                vo.reviewedAt(),
                vo.refundedAt(),
                vo.createdAt()
            );
        }
    }
}
