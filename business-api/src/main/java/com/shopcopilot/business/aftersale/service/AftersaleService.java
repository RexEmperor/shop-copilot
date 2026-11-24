package com.shopcopilot.business.aftersale.service;

import com.shopcopilot.business.aftersale.dto.AftersaleCreateRequest;
import com.shopcopilot.business.aftersale.query.AftersaleQuery;
import com.shopcopilot.business.aftersale.vo.AftersaleVO;
import com.shopcopilot.business.common.PageResult;

public interface AftersaleService {

    Long create(AftersaleCreateRequest request);

    AftersaleVO approve(Long id);

    AftersaleVO reject(Long id, String reviewRemark);

    AftersaleVO completeRefund(Long id);

    AftersaleVO getById(Long id);

    PageResult<AftersaleVO> page(AftersaleQuery query);
}
