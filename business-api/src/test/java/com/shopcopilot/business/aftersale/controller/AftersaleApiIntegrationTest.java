package com.shopcopilot.business.aftersale.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopcopilot.business.order.entity.Order;
import com.shopcopilot.business.order.enums.OrderStatus;
import com.shopcopilot.business.order.mapper.OrderMapper;
import com.shopcopilot.business.product.entity.Product;
import com.shopcopilot.business.product.enums.ProductStatus;
import com.shopcopilot.business.product.mapper.ProductMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AftersaleApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Test
    @DisplayName("售后可依次完成创建、查询、审核通过和确认退款")
    void should_completeAftersaleLifecycle_when_transitionsLegal() throws Exception {
        Product product = insertProduct();
        Order order = insertOrder(product, OrderStatus.PAID);

        MvcResult createResult = mockMvc.perform(post("/api/aftersales")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(order, 0, "商品与描述不符")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data", notNullValue()))
            .andReturn();
        long ticketId = objectMapper.readTree(createResult.getResponse().getContentAsString())
            .path("data").asLong();

        mockMvc.perform(get("/api/aftersales/{id}", ticketId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(ticketId))
            .andExpect(jsonPath("$.data.orderNo").value(order.getOrderNo()))
            .andExpect(jsonPath("$.data.type").value(0))
            .andExpect(jsonPath("$.data.status").value(0))
            .andExpect(jsonPath("$.data.statusText").value("待审核"))
            .andExpect(jsonPath("$.data.refundCents").value(200))
            .andExpect(jsonPath("$.data.reason").value("商品与描述不符"))
            .andExpect(jsonPath("$.data.reviewedAt").value(nullValue()))
            .andExpect(jsonPath("$.data.refundedAt").value(nullValue()));

        mockMvc.perform(get("/api/aftersales")
                .param("orderNo", order.getOrderNo())
                .param("status", "0")
                .param("page", "1")
                .param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.page").value(1))
            .andExpect(jsonPath("$.data.size").value(10))
            .andExpect(jsonPath("$.data.records[0].id").value(ticketId));

        mockMvc.perform(post("/api/aftersales/{id}/refund/complete", ticketId))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.message").value("售后状态不能从待审核变更为已退款"));

        mockMvc.perform(post("/api/aftersales/{id}/approve", ticketId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(1))
            .andExpect(jsonPath("$.data.statusText").value("退款中"))
            .andExpect(jsonPath("$.data.reviewedAt", notNullValue()))
            .andExpect(jsonPath("$.data.refundedAt").value(nullValue()));

        mockMvc.perform(post("/api/aftersales/{id}/approve", ticketId))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("售后状态不能从退款中变更为退款中"));

        mockMvc.perform(post("/api/aftersales/{id}/reject", ticketId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewRemark\":\"不能重复审核\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.message").value("售后状态不能从退款中变更为已拒绝"));

        mockMvc.perform(post("/api/aftersales/{id}/refund/complete", ticketId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(2))
            .andExpect(jsonPath("$.data.statusText").value("已退款"))
            .andExpect(jsonPath("$.data.reviewedAt", notNullValue()))
            .andExpect(jsonPath("$.data.refundedAt", notNullValue()));

        mockMvc.perform(post("/api/aftersales/{id}/refund/complete", ticketId))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.message").value("售后状态不能从已退款变更为已退款"));

        assertEquals(OrderStatus.PAID, orderMapper.selectById(order.getId()).getStatus());
        assertEquals(10, productMapper.selectById(product.getId()).getStock());
    }

    @ParameterizedTest
    @CsvSource({
        "PAID,0",
        "SHIPPED,1",
        "DELIVERED,1"
    })
    @DisplayName("合法订单和售后类型可创建工单")
    void should_createTicket_when_orderAndTypeEligible(OrderStatus status, int type) throws Exception {
        Order order = insertOrder(status);

        MvcResult result = mockMvc.perform(post("/api/aftersales")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(order, type, "售后原因")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn();
        long ticketId = objectMapper.readTree(result.getResponse().getContentAsString())
            .path("data").asLong();

        mockMvc.perform(get("/api/aftersales/{id}", ticketId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.orderNo").value(order.getOrderNo()))
            .andExpect(jsonPath("$.data.type").value(type))
            .andExpect(jsonPath("$.data.status").value(0))
            .andExpect(jsonPath("$.data.refundCents").value(200));
    }

    @Test
    @DisplayName("拒绝后允许同一订单重新发起售后")
    void should_createNewTicket_when_previousTicketRejected() throws Exception {
        Order order = insertOrder(OrderStatus.PAID);
        long firstId = createTicket(order, 0, "第一次申请");

        mockMvc.perform(post("/api/aftersales/{id}/reject", firstId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewRemark\":\"证据不足\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(3))
            .andExpect(jsonPath("$.data.statusText").value("已拒绝"))
            .andExpect(jsonPath("$.data.reviewRemark").value("证据不足"))
            .andExpect(jsonPath("$.data.reviewedAt", notNullValue()));

        mockMvc.perform(post("/api/aftersales/{id}/reject", firstId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewRemark\":\"重复拒绝\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("售后状态不能从已拒绝变更为已拒绝"));

        long secondId = createTicket(order, 0, "补充证据后重新申请");

        mockMvc.perform(get("/api/aftersales")
                .param("orderNo", order.getOrderNo())
                .param("status", "3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].id").value(firstId));

        mockMvc.perform(get("/api/aftersales")
                .param("orderNo", order.getOrderNo())
                .param("status", "0"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].id").value(secondId));
    }

    @Test
    @DisplayName("售后列表按订单过滤并可正确翻页")
    void should_filterAndPaginate_when_multipleTicketsExist() throws Exception {
        Order order = insertOrder(OrderStatus.PAID);
        List<Long> ticketIds = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            long ticketId = createTicket(order, 0, "申请" + index);
            rejectTicket(ticketId);
            ticketIds.add(ticketId);
        }
        Order decoyOrder = insertOrder(OrderStatus.PAID);
        rejectTicket(createTicket(decoyOrder, 0, "其他订单"));

        MvcResult firstPage = mockMvc.perform(get("/api/aftersales")
                .param("orderNo", order.getOrderNo())
                .param("status", "3")
                .param("page", "1")
                .param("size", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(3))
            .andExpect(jsonPath("$.data.records.length()").value(2))
            .andReturn();
        MvcResult secondPage = mockMvc.perform(get("/api/aftersales")
                .param("orderNo", order.getOrderNo())
                .param("status", "3")
                .param("page", "2")
                .param("size", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(3))
            .andExpect(jsonPath("$.data.records.length()").value(1))
            .andReturn();

        List<Long> pagedIds = new ArrayList<>();
        for (MvcResult page : List.of(firstPage, secondPage)) {
            JsonNode records = objectMapper.readTree(page.getResponse().getContentAsString())
                .path("data").path("records");
            for (JsonNode record : records) {
                pagedIds.add(record.path("id").asLong());
            }
        }
        pagedIds.sort(Long::compareTo);
        ticketIds.sort(Long::compareTo);
        assertEquals(ticketIds, pagedIds);
    }

    @Test
    @DisplayName("客户端不能覆盖退款金额、用户和售后状态")
    void should_ignoreClientControlledFields_when_creatingTicket() throws Exception {
        Order order = insertOrder(OrderStatus.PAID);
        String body = """
            {
              "orderNo": "%s",
              "type": 0,
              "reason": "原因",
              "refundCents": 1,
              "userId": 999,
              "status": 2
            }
            """.formatted(order.getOrderNo());

        MvcResult result = mockMvc.perform(post("/api/aftersales")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn();
        long ticketId = objectMapper.readTree(result.getResponse().getContentAsString())
            .path("data").asLong();

        mockMvc.perform(get("/api/aftersales/{id}", ticketId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").value(10001))
            .andExpect(jsonPath("$.data.status").value(0))
            .andExpect(jsonPath("$.data.refundCents").value(200));
    }

    @Test
    @DisplayName("订单已有退款中售后时再次创建返回 409")
    void should_returnConflict_when_activeTicketExists() throws Exception {
        Order order = insertOrder(OrderStatus.PAID);
        long ticketId = createTicket(order, 0, "第一次申请");
        mockMvc.perform(post("/api/aftersales/{id}/approve", ticketId))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/aftersales")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(order, 0, "重复申请")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.message").value("该订单已有进行中的售后工单"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"reviewRemark\":null}",
        "{\"reviewRemark\":\"   \"}"
    })
    @DisplayName("拒绝售后时审核备注缺失或为空返回 400")
    void should_returnBadRequest_when_rejectRemarkBlank(String body) throws Exception {
        Order order = insertOrder(OrderStatus.PAID);
        long ticketId = createTicket(order, 0, "原因");

        mockMvc.perform(post("/api/aftersales/{id}/reject", ticketId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message", containsString("reviewRemark")));
    }

    @Test
    @DisplayName("拒绝售后时审核备注超过 500 字返回 400")
    void should_returnBadRequest_when_rejectRemarkTooLong() throws Exception {
        Order order = insertOrder(OrderStatus.PAID);
        long ticketId = createTicket(order, 0, "原因");
        String body = objectMapper.writeValueAsString(Map.of("reviewRemark", "x".repeat(501)));

        mockMvc.perform(post("/api/aftersales/{id}/reject", ticketId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("reviewRemark")));
    }

    @Test
    @DisplayName("拒绝售后时审核备注恰好 500 字可通过")
    void should_acceptRejectRemark_when_lengthExactly500() throws Exception {
        Order order = insertOrder(OrderStatus.PAID);
        long ticketId = createTicket(order, 0, "原因");
        String remark = "x".repeat(500);
        String body = objectMapper.writeValueAsString(Map.of("reviewRemark", remark));

        mockMvc.perform(post("/api/aftersales/{id}/reject", ticketId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reviewRemark").value(remark));
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CREATED", "CANCELLED"})
    @DisplayName("待支付或已取消订单发起售后返回 409")
    void should_returnConflict_when_orderStatusNotEligible(OrderStatus status) throws Exception {
        Order order = insertOrder(status);

        mockMvc.perform(post("/api/aftersales")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(order, 0, "原因")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.message").value("当前订单状态不允许发起售后"));
    }

    @Test
    @DisplayName("已支付订单发起退货退款返回 409")
    void should_returnConflict_when_returnRefundBeforeShipment() throws Exception {
        Order order = insertOrder(OrderStatus.PAID);

        mockMvc.perform(post("/api/aftersales")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(order, 1, "原因")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.message").value("当前订单状态不允许退货退款"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"type\":0,\"reason\":\"原因\"}",
        "{\"orderNo\":\"SO_TEST\",\"reason\":\"原因\"}",
        "{\"orderNo\":\"SO_TEST\",\"type\":null,\"reason\":\"原因\"}",
        "{\"orderNo\":\"SO_TEST\",\"type\":0,\"reason\":\"   \"}",
        "{\"orderNo\":\"SO_TEST\",\"type\":0}",
        "{\"orderNo\":\"SO_TEST\",\"type\":0,\"reason\":null}",
        "{\"orderNo\":\"SO_TEST\",\"type\":2,\"reason\":\"原因\"}"
    })
    @DisplayName("创建售后参数非法时返回 400")
    void should_returnBadRequest_when_createRequestInvalid(String body) throws Exception {
        mockMvc.perform(post("/api/aftersales")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("售后原因超过 500 字返回 400")
    void should_returnBadRequest_when_reasonTooLong() throws Exception {
        Order order = insertOrder(OrderStatus.PAID);
        String body = objectMapper.writeValueAsString(Map.of(
            "orderNo", order.getOrderNo(),
            "type", 0,
            "reason", "x".repeat(501)
        ));

        mockMvc.perform(post("/api/aftersales")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("reason")));
    }

    @Test
    @DisplayName("创建售后时订单不存在返回 404")
    void should_returnNotFound_when_orderMissing() throws Exception {
        mockMvc.perform(post("/api/aftersales")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderNo\":\"SO_NOT_FOUND\",\"type\":0,\"reason\":\"原因\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.message").value("订单不存在"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"detail", "approve", "reject", "refund/complete"})
    @DisplayName("不存在的售后工单返回 404")
    void should_returnNotFound_when_ticketMissing(String action) throws Exception {
        if ("detail".equals(action)) {
            mockMvc.perform(get("/api/aftersales/{id}", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("售后工单不存在"));
            return;
        }

        mockMvc.perform(post("/api/aftersales/{id}/{action}", 999999L, action)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewRemark\":\"原因\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("售后工单不存在"));
    }

    @Test
    @DisplayName("非法售后 ID 格式返回 400")
    void should_returnBadRequest_when_ticketIdMalformed() throws Exception {
        mockMvc.perform(get("/api/aftersales/{id}", "abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @ParameterizedTest
    @CsvSource({"0,20", "1,101"})
    @DisplayName("分页参数超出范围返回 400")
    void should_returnBadRequest_when_pageInvalid(int page, int size) throws Exception {
        mockMvc.perform(get("/api/aftersales")
                .param("page", String.valueOf(page))
                .param("size", String.valueOf(size)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("售后状态过滤值非法返回 400")
    void should_returnBadRequest_when_statusFilterInvalid() throws Exception {
        mockMvc.perform(get("/api/aftersales").param("status", "4"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("存在待审核售后时禁止取消订单")
    void should_blockOrderCancellation_when_aftersalePending() throws Exception {
        Order order = insertOrder(OrderStatus.PAID);
        createTicket(order, 0, "原因");

        mockMvc.perform(post("/api/orders/{id}/cancel", order.getId()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.message").value("该订单存在售后记录，不能取消"));
    }

    @Test
    @DisplayName("存在退款中售后时禁止取消订单")
    void should_blockOrderCancellation_when_aftersaleRefunding() throws Exception {
        Order order = insertOrder(OrderStatus.PAID);
        long ticketId = createTicket(order, 0, "原因");
        mockMvc.perform(post("/api/aftersales/{id}/approve", ticketId))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/orders/{id}/cancel", order.getId()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("该订单存在售后记录，不能取消"));
    }

    @Test
    @DisplayName("存在已退款售后时禁止取消订单")
    void should_blockOrderCancellation_when_aftersaleRefunded() throws Exception {
        Order order = insertOrder(OrderStatus.PAID);
        long ticketId = createTicket(order, 0, "原因");
        mockMvc.perform(post("/api/aftersales/{id}/approve", ticketId))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/aftersales/{id}/refund/complete", ticketId))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/orders/{id}/cancel", order.getId()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("该订单存在售后记录，不能取消"));
    }

    @Test
    @DisplayName("售后已拒绝时允许取消订单并恢复库存")
    void should_allowOrderCancellation_when_aftersaleRejected() throws Exception {
        Product product = insertProduct();
        Order order = insertOrder(product, OrderStatus.PAID);
        long ticketId = createTicket(order, 0, "原因");
        mockMvc.perform(post("/api/aftersales/{id}/reject", ticketId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewRemark\":\"证据不足\"}"))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/orders/{id}/cancel", order.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(4));

        assertEquals(12, productMapper.selectById(product.getId()).getStock());
        assertEquals(OrderStatus.CANCELLED, orderMapper.selectById(order.getId()).getStatus());
    }

    private long createTicket(Order order, int type, String reason) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/aftersales")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(order, type, reason)))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .path("data").asLong();
    }

    private void rejectTicket(long ticketId) throws Exception {
        mockMvc.perform(post("/api/aftersales/{id}/reject", ticketId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewRemark\":\"测试拒绝\"}"))
            .andExpect(status().isOk());
    }

    private String createBody(Order order, int type, String reason) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "orderNo", order.getOrderNo(),
            "type", type,
            "reason", reason
        ));
    }

    private Order insertOrder(OrderStatus status) {
        return insertOrder(insertProduct(), status);
    }

    private Order insertOrder(Product product, OrderStatus status) {
        Order order = new Order();
        order.setOrderNo("SO_AFTERSALE_API_" + UUID.randomUUID().toString().substring(0, 8));
        order.setUserId(10001L);
        order.setProductId(product.getId());
        order.setProductNameSnapshot(product.getName());
        order.setUnitPriceCents(100L);
        order.setQuantity(2);
        order.setTotalAmountCents(200L);
        order.setStatus(status);
        order.setVersion(0);
        order.setReceiverName("郭克");
        order.setReceiverPhone("13800138000");
        order.setShippingAddress("北京市朝阳区测试路 1 号");
        LocalDateTime now = LocalDateTime.now();
        order.setCreatedAt(now.minusDays(1));
        order.setUpdatedAt(now);
        if (status == OrderStatus.PAID || status == OrderStatus.SHIPPED
            || status == OrderStatus.DELIVERED) {
            order.setPaidAt(now.minusHours(12));
        }
        if (status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERED) {
            order.setShippedAt(now.minusHours(6));
        }
        if (status == OrderStatus.DELIVERED) {
            order.setDeliveredAt(now.minusHours(1));
        }
        if (status == OrderStatus.CANCELLED) {
            order.setCancelledAt(now.minusHours(1));
        }
        orderMapper.insert(order);
        return order;
    }

    private Product insertProduct() {
        Product product = new Product();
        product.setName("售后接口测试商品");
        product.setCategory("测试");
        product.setDescription("用于验证售后接口");
        product.setPriceCents(100L);
        product.setStock(10);
        product.setStatus(ProductStatus.OFF_SALE);
        product.setVersion(0);
        productMapper.insert(product);
        return product;
    }
}
