package com.shopcopilot.business.aftersale.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "售后工单返回对象")
public record AftersaleVO(
    @Schema(description = "售后工单ID")
    Long id,

    @Schema(description = "业务售后单号")
    String ticketNo,

    @Schema(description = "业务订单号")
    String orderNo,

    @Schema(description = "用户ID")
    Long userId,

    @Schema(description = "售后类型：0-仅退款，1-退货退款")
    Integer type,

    @Schema(description = "售后状态：0-待审核，1-退款中，2-已退款，3-已拒绝")
    Integer status,

    @Schema(description = "售后状态描述")
    String statusText,

    @Schema(description = "退款金额，单位分")
    Long refundCents,

    @Schema(description = "售后原因")
    String reason,

    @Schema(description = "审核备注")
    String reviewRemark,

    @Schema(description = "审核通过或拒绝时间")
    LocalDateTime reviewedAt,

    @Schema(description = "退款完成时间")
    LocalDateTime refundedAt,

    @Schema(description = "创建时间")
    LocalDateTime createdAt,

    @Schema(description = "更新时间")
    LocalDateTime updatedAt
) {
}
