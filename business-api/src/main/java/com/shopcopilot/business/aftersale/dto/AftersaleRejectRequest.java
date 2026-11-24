package com.shopcopilot.business.aftersale.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "拒绝售后入参")
public record AftersaleRejectRequest(
    @Schema(description = "审核备注", example = "证据不足")
    @NotBlank(message = "审核备注不能为空")
    @Size(max = 500, message = "审核备注不能超过500个字符")
    String reviewRemark
) {
}
