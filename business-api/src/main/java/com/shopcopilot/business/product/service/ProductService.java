package com.shopcopilot.business.product.service;

import com.shopcopilot.business.common.PageResult;
import com.shopcopilot.business.product.dto.ProductSaveRequest;
import com.shopcopilot.business.product.query.ProductQuery;
import com.shopcopilot.business.product.vo.ProductVO;

public interface ProductService {

    PageResult<ProductVO> page(ProductQuery query);

    ProductVO getById(Long id);

    Long create(ProductSaveRequest request);

    void update(Long id, ProductSaveRequest request);

    void changeStatus(Long id, Integer status);
}
