package com.shopcopilot.business.order.controller;

import com.shopcopilot.business.common.Result;
import com.shopcopilot.business.order.dto.OrderCreateRequest;
import com.shopcopilot.business.order.enums.OrderStatus;
import com.shopcopilot.business.order.service.OrderService;
import com.shopcopilot.business.order.vo.OrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "订单管理")
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(summary = "创建订单")
    @PostMapping
    public Result<OrderVO> create(@RequestBody @Valid OrderCreateRequest request) {
        return Result.ok(orderService.create(request));
    }

    @Operation(summary = "支付订单")
    @PostMapping("/{id}/pay")
    public Result<OrderVO> pay(@PathVariable("id") Long id) {
        return Result.ok(orderService.transition(id, OrderStatus.PAID));
    }

    @Operation(summary = "订单发货")
    @PostMapping("/{id}/ship")
    public Result<OrderVO> ship(@PathVariable("id") Long id) {
        return Result.ok(orderService.transition(id, OrderStatus.SHIPPED));
    }

    @Operation(summary = "确认收货")
    @PostMapping("/{id}/deliver")
    public Result<OrderVO> deliver(@PathVariable("id") Long id) {
        return Result.ok(orderService.transition(id, OrderStatus.DELIVERED));
    }

    @Operation(summary = "取消订单")
    @PostMapping("/{id}/cancel")
    public Result<OrderVO> cancel(@PathVariable("id") Long id) {
        return Result.ok(orderService.transition(id, OrderStatus.CANCELLED));
    }
}
