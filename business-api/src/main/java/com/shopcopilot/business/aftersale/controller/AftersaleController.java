package com.shopcopilot.business.aftersale.controller;

import com.shopcopilot.business.aftersale.dto.AftersaleCreateRequest;
import com.shopcopilot.business.aftersale.dto.AftersaleRejectRequest;
import com.shopcopilot.business.aftersale.query.AftersaleQuery;
import com.shopcopilot.business.aftersale.service.AftersaleService;
import com.shopcopilot.business.aftersale.vo.AftersaleVO;
import com.shopcopilot.business.common.PageResult;
import com.shopcopilot.business.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "售后管理", description = "售后创建、审核、退款和查询")
@RestController
@RequestMapping("/api/aftersales")
public class AftersaleController {

    private final AftersaleService aftersaleService;

    public AftersaleController(AftersaleService aftersaleService) {
        this.aftersaleService = aftersaleService;
    }

    @PostMapping
    @Operation(summary = "创建售后工单")
    public Result<Long> create(@Valid @RequestBody AftersaleCreateRequest request) {
        return Result.ok(aftersaleService.create(request));
    }

    @GetMapping
    @Operation(summary = "售后分页列表")
    public Result<PageResult<AftersaleVO>> list(@Valid AftersaleQuery query) {
        return Result.ok(aftersaleService.page(query));
    }

    @GetMapping("/{id}")
    @Operation(summary = "售后工单详情")
    public Result<AftersaleVO> detail(@PathVariable Long id) {
        return Result.ok(aftersaleService.getById(id));
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "审核通过售后工单")
    public Result<AftersaleVO> approve(@PathVariable Long id) {
        return Result.ok(aftersaleService.approve(id));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "拒绝售后工单")
    public Result<AftersaleVO> reject(@PathVariable Long id,
                                      @Valid @RequestBody AftersaleRejectRequest request) {
        return Result.ok(aftersaleService.reject(id, request.reviewRemark()));
    }

    @PostMapping("/{id}/refund/complete")
    @Operation(summary = "确认退款完成")
    public Result<AftersaleVO> completeRefund(@PathVariable Long id) {
        return Result.ok(aftersaleService.completeRefund(id));
    }
}
