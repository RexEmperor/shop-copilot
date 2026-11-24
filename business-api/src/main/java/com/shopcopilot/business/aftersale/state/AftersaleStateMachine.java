package com.shopcopilot.business.aftersale.state;

import com.shopcopilot.business.aftersale.enums.AftersaleStatus;
import com.shopcopilot.business.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AftersaleStateMachine {

    public AftersaleStatus transition(AftersaleStatus current, AftersaleStatus target) {
        boolean allowed = switch (current) {
            case PENDING -> target == AftersaleStatus.REFUNDING || target == AftersaleStatus.REJECTED;
            case REFUNDING -> target == AftersaleStatus.REFUNDED;
            case REFUNDED, REJECTED -> false;
        };
        if (!allowed) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "售后状态不能从" + current.getText() + "变更为" + target.getText()
            );
        }
        return target;
    }
}
