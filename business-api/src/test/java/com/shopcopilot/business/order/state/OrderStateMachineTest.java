package com.shopcopilot.business.order.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shopcopilot.business.common.BusinessException;
import com.shopcopilot.business.order.enums.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

class OrderStateMachineTest {

    private final OrderStateMachine stateMachine = new OrderStateMachine();

    @ParameterizedTest
    @CsvSource({
        "CREATED, PAID",
        "CREATED, CANCELLED",
        "PAID, SHIPPED",
        "PAID, CANCELLED",
        "SHIPPED, DELIVERED",
        "SHIPPED, CANCELLED"
    })
    void should_allowLegalTransition(OrderStatus current, OrderStatus target) {
        assertEquals(target, stateMachine.transition(current, target));
    }

    @ParameterizedTest
    @CsvSource({
        "CREATED, SHIPPED",
        "CREATED, DELIVERED",
        "PAID, CREATED",
        "PAID, DELIVERED",
        "PAID, PAID",
        "SHIPPED, CREATED",
        "SHIPPED, PAID",
        "SHIPPED, SHIPPED",
        "DELIVERED, PAID",
        "DELIVERED, CANCELLED",
        "CANCELLED, CREATED",
        "CANCELLED, PAID"
    })
    void should_rejectIllegalTransition(OrderStatus current, OrderStatus target) {
        BusinessException exception = assertThrows(BusinessException.class,
            () -> stateMachine.transition(current, target));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertTrue(exception.getMessage().contains("订单状态不能从"));
    }

    @Test
    void should_includeCurrentAndTargetStatus_inErrorMessage() {
        BusinessException exception = assertThrows(BusinessException.class,
            () -> stateMachine.transition(OrderStatus.CREATED, OrderStatus.SHIPPED));

        assertEquals("订单状态不能从待支付变更为已发货", exception.getMessage());
    }
}
