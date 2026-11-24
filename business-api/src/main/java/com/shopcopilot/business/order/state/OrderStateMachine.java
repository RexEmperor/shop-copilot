package com.shopcopilot.business.order.state;

import com.shopcopilot.business.common.BusinessException;
import com.shopcopilot.business.order.enums.OrderStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class OrderStateMachine {

    public OrderStatus transition(OrderStatus current, OrderStatus target) {
        if (!current.canTransitionTo(target)) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "订单状态不能从" + current.getDescription() + "变更为" + target.getDescription()
            );
        }
        return target;
    }
}
