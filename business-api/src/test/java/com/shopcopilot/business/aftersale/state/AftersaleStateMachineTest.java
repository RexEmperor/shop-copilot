package com.shopcopilot.business.aftersale.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shopcopilot.business.aftersale.enums.AftersaleStatus;
import com.shopcopilot.business.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

class AftersaleStateMachineTest {

    private final AftersaleStateMachine stateMachine = new AftersaleStateMachine();

    @ParameterizedTest
    @CsvSource({
        "PENDING, REFUNDING",
        "PENDING, REJECTED",
        "REFUNDING, REFUNDED"
    })
    void should_allowLegalTransition(AftersaleStatus current, AftersaleStatus target) {
        assertEquals(target, stateMachine.transition(current, target));
    }

    @ParameterizedTest
    @CsvSource({
        "PENDING, PENDING",
        "PENDING, REFUNDED",
        "REFUNDING, PENDING",
        "REFUNDING, REJECTED",
        "REFUNDING, REFUNDING",
        "REFUNDED, PENDING",
        "REFUNDED, REFUNDING",
        "REFUNDED, REFUNDED",
        "REFUNDED, REJECTED",
        "REJECTED, PENDING",
        "REJECTED, REFUNDING",
        "REJECTED, REFUNDED",
        "REJECTED, REJECTED"
    })
    void should_rejectIllegalTransition(AftersaleStatus current, AftersaleStatus target) {
        BusinessException exception = assertThrows(BusinessException.class,
            () -> stateMachine.transition(current, target));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertTrue(exception.getMessage().contains("售后状态不能从"));
    }

    @Test
    void should_includeCurrentAndTargetStatus_inErrorMessage() {
        BusinessException exception = assertThrows(BusinessException.class,
            () -> stateMachine.transition(AftersaleStatus.PENDING, AftersaleStatus.REFUNDED));

        assertEquals("售后状态不能从待审核变更为已退款", exception.getMessage());
    }
}
