package com.shopcopilot.business.order.service;

import com.shopcopilot.business.common.PageResult;
import com.shopcopilot.business.order.dto.OrderCreateRequest;
import com.shopcopilot.business.order.enums.OrderStatus;
import com.shopcopilot.business.order.query.OrderQuery;
import com.shopcopilot.business.order.vo.OrderVO;

public interface OrderService {

    OrderVO create(OrderCreateRequest request);

    OrderVO transition(Long id, OrderStatus targetStatus);

    OrderVO getById(Long id);

    PageResult<OrderVO> page(OrderQuery query);
}
