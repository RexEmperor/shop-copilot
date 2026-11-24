package com.shopcopilot.business.aftersale.query;

import com.shopcopilot.business.common.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@Schema(description = "售后分页查询条件")
public class AftersaleQuery extends PageQuery {

    @Schema(description = "业务订单号")
    @Size(max = 32, message = "订单号不能超过32个字符")
    private String orderNo;

    @Schema(description = "售后状态：0-待审核，1-退款中，2-已退款，3-已拒绝")
    @Min(value = 0, message = "售后状态不能小于0")
    @Max(value = 3, message = "售后状态不能大于3")
    private Integer status;

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
