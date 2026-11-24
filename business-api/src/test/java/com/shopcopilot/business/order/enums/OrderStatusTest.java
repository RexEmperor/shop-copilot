package com.shopcopilot.business.order.enums;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OrderStatusTest {

    @ParameterizedTest
    @CsvSource({
        "0, CREATED",
        "1, PAID",
        "2, SHIPPED",
        "3, DELIVERED",
        "4, CANCELLED"
    })
    void should_returnStatus_when_codeMatches(int code, OrderStatus expected) {
        assertEquals(expected, OrderStatus.fromCode(code));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 5})
    void should_throwException_when_codeUnknown(int code) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> OrderStatus.fromCode(code));

        assertEquals("未知订单状态: " + code, exception.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
        "CREATED, false",
        "PAID, false",
        "SHIPPED, false",
        "DELIVERED, true",
        "CANCELLED, true"
    })
    void should_identifyTerminalStatus(OrderStatus status, boolean terminal) {
        assertEquals(terminal, status.isTerminal());
    }

    @Test
    void should_exposeStableCodesAndDescriptions() {
        assertEquals(0, OrderStatus.CREATED.getCode());
        assertEquals("待支付", OrderStatus.CREATED.getDescription());
        assertEquals(4, OrderStatus.CANCELLED.getCode());
        assertEquals("已取消", OrderStatus.CANCELLED.getDescription());
    }

    @Test
    void should_notAllowTransitionToNull() {
        assertFalse(OrderStatus.CREATED.canTransitionTo(null));
    }
}
