package com.shopcopilot.business.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ResultTest {

    @Test
    void should_returnSuccess_withData() {
        Result<String> result = Result.ok("hello");

        assertEquals(200, result.code());
        assertEquals("success", result.message());
        assertEquals("hello", result.data());
        assertNotNull(result.timestamp());
    }

    @Test
    void should_returnError_withStatusAndMessage() {
        Result<Void> result = Result.error(HttpStatus.NOT_FOUND, "订单不存在");

        assertEquals(404, result.code());
        assertEquals("订单不存在", result.message());
        assertNull(result.data());
        assertNotNull(result.timestamp());
    }
}
