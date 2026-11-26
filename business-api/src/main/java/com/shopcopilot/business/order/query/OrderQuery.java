package com.shopcopilot.business.order.query;

import com.shopcopilot.business.common.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@Schema(description = "订单分页查询条件")
public class OrderQuery extends PageQuery {

    @Schema(description = "业务订单号")
    @Size(max = 32, message = "订单号不能超过32个字符")
    private String orderNo;

    @Schema(description = "订单状态：0-待支付，1-已支付，2-已发货，3-已完成，4-已取消")
    @Min(value = 0, message = "订单状态不能小于0")
    @Max(value = 4, message = "订单状态不能大于4")
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
