package com.shopcopilot.business.order.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shopcopilot.business.order.entity.Order;
import com.shopcopilot.business.order.mapper.OrderMapper;
import com.shopcopilot.business.product.entity.Product;
import com.shopcopilot.business.product.mapper.ProductMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OrderStateTransitionApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private ProductMapper productMapper;

    @Test
    @DisplayName("订单可以依次支付、发货、确认收货")
    void should_completeOrderLifecycle_when_transitionsLegal() throws Exception {
        mockMvc.perform(post("/api/orders/1/pay"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(1))
            .andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.paidAt", notNullValue()));

        mockMvc.perform(post("/api/orders/1/ship"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(2))
            .andExpect(jsonPath("$.data.version").value(2))
            .andExpect(jsonPath("$.data.shippedAt", notNullValue()));

        mockMvc.perform(post("/api/orders/1/deliver"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(3))
            .andExpect(jsonPath("$.data.version").value(3))
            .andExpect(jsonPath("$.data.deliveredAt", notNullValue()));

        Order order = orderMapper.selectById(1L);
        assertEquals(3, order.getStatus().getCode());
        assertEquals(3, order.getVersion());
        assertNotNull(order.getPaidAt());
        assertNotNull(order.getShippedAt());
        assertNotNull(order.getDeliveredAt());

        mockMvc.perform(post("/api/orders/1/deliver"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.message").value("订单状态不能从已完成变更为已完成"))
            .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @ParameterizedTest
    @CsvSource({
        "1,ship",
        "1,deliver",
        "2,pay",
        "2,deliver",
        "3,pay",
        "3,ship",
        "4,pay",
        "4,ship",
        "4,deliver",
        "4,cancel"
    })
    @DisplayName("重复、越级、回退和终态流转均返回 409")
    void should_returnConflict_when_transitionIllegal(Long orderId, String action) throws Exception {
        mockMvc.perform(post("/api/orders/{id}/{action}", orderId, action))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.message", containsString("订单状态不能从")))
            .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @ParameterizedTest
    @CsvSource({
        "1,1,1,120",
        "2,2,1,80",
        "3,3,2,60"
    })
    @DisplayName("非终态订单取消后恢复对应商品库存")
    void should_restoreStock_when_orderCancelled(
            Long orderId, Long productId, int quantity, int stockBefore) throws Exception {
        mockMvc.perform(post("/api/orders/{id}/cancel", orderId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(4))
            .andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.cancelledAt", notNullValue()));

        Order order = orderMapper.selectById(orderId);
        Product product = productMapper.selectById(productId);
        assertEquals(4, order.getStatus().getCode());
        assertNotNull(order.getCancelledAt());
        assertEquals(stockBefore + quantity, product.getStock());
        assertEquals(1, product.getVersion());
    }

    @Test
    @DisplayName("不存在的订单返回 404")
    void should_returnNotFound_when_orderMissing() throws Exception {
        mockMvc.perform(post("/api/orders/999999/pay"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("订单不存在"))
            .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("非法订单ID格式返回 400")
    void should_returnBadRequest_when_orderIdMalformed() throws Exception {
        mockMvc.perform(post("/api/orders/abc/pay"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("参数格式错误: id"))
            .andExpect(jsonPath("$.data").value(nullValue()));
    }
}
