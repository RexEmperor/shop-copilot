package com.shopcopilot.business.product.controller;

import com.shopcopilot.business.common.PageResult;
import com.shopcopilot.business.common.Result;
import com.shopcopilot.business.common.ratelimit.RateLimit;
import com.shopcopilot.business.product.dto.ProductSaveRequest;
import com.shopcopilot.business.product.dto.ProductStatusRequest;
import com.shopcopilot.business.product.query.ProductQuery;
import com.shopcopilot.business.product.service.ProductService;
import com.shopcopilot.business.product.vo.ProductVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "商品管理", description = "商品查询、创建、修改和上下架")
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    @RateLimit(maxRequests = 120, windowSeconds = 60)
    @Operation(summary = "商品分页列表", description = "默认只返回上架商品；传 status=0 可查询下架商品")
    public Result<PageResult<ProductVO>> list(@Valid ProductQuery query) {
        return Result.ok(productService.page(query));
    }

    @GetMapping("/{id}")
    @RateLimit(maxRequests = 120, windowSeconds = 60)
    @Operation(summary = "商品详情")
    public Result<ProductVO> detail(@PathVariable Long id) {
        return Result.ok(productService.getById(id));
    }

    @PostMapping
    @RateLimit(maxRequests = 60, windowSeconds = 60)
    @Operation(summary = "创建商品", description = "新商品默认上架")
    public Result<Long> create(@Valid @RequestBody ProductSaveRequest request) {
        return Result.ok(productService.create(request));
    }

    @PutMapping("/{id}")
    @RateLimit(maxRequests = 60, windowSeconds = 60)
    @Operation(summary = "修改商品")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody ProductSaveRequest request) {
        productService.update(id, request);
        return Result.ok();
    }

    @PutMapping("/{id}/status")
    @RateLimit(maxRequests = 60, windowSeconds = 60)
    @Operation(summary = "商品上下架")
    public Result<Void> changeStatus(@PathVariable Long id,
                                     @Valid @RequestBody ProductStatusRequest request) {
        productService.changeStatus(id, request.status());
        return Result.ok();
    }
}
