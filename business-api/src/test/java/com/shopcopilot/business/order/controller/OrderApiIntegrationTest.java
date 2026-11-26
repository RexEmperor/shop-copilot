package com.shopcopilot.business.order.controller;

import static org.hamcrest.Matchers.hasSize;
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
import com.shopcopilot.business.order.mapper.OrderMapper;
import com.shopcopilot.business.product.entity.Product;
import com.shopcopilot.business.product.mapper.ProductMapper;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OrderApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void clearRateLimitKeys() {
        Set<String> keys = stringRedisTemplate.keys("shop:ratelimit:OrderController#*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("创建订单成功后扣减库存并保存后端价格快照")
    void should_createOrderAndDeductStock_when_requestValid() throws Exception {
        long orderCountBefore = orderMapper.selectCount(null);

        MvcResult result = mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "productId": 1,
                      "quantity": 2,
                      "receiverName": "郭克",
                      "receiverPhone": "13800138000",
                      "shippingAddress": "北京市朝阳区测试路 1 号",
                      "unitPriceCents": 1,
                      "totalAmountCents": 1
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.id", notNullValue()))
            .andExpect(jsonPath("$.data.orderNo", notNullValue()))
            .andExpect(jsonPath("$.data.userId").value(10001))
            .andExpect(jsonPath("$.data.productId").value(1))
            .andExpect(jsonPath("$.data.productNameSnapshot").value("无线蓝牙耳机 Pro"))
            .andExpect(jsonPath("$.data.unitPriceCents").value(19900))
            .andExpect(jsonPath("$.data.quantity").value(2))
            .andExpect(jsonPath("$.data.totalAmountCents").value(39800))
            .andExpect(jsonPath("$.data.status").value(0))
            .andExpect(jsonPath("$.data.statusText").value("待支付"))
            .andExpect(jsonPath("$.data.version").value(0))
            .andExpect(jsonPath("$.data.createdAt", notNullValue()))
            .andExpect(jsonPath("$.data.updatedAt", notNullValue()))
            .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        long orderId = response.path("data").path("id").asLong();
        Order order = orderMapper.selectById(orderId);
        Product product = productMapper.selectById(1L);

        assertEquals(19900L, order.getUnitPriceCents());
        assertEquals(39800L, order.getTotalAmountCents());
        assertEquals("无线蓝牙耳机 Pro", order.getProductNameSnapshot());
        assertEquals(118, product.getStock());
        assertEquals(1, product.getVersion());
        assertEquals(orderCountBefore + 1, orderMapper.selectCount(null));
    }

    @Test
    @DisplayName("库存不足时返回 409 且库存与订单数量不变")
    void should_returnConflict_when_stockInsufficient() throws Exception {
        long orderCountBefore = orderMapper.selectCount(null);

        mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "productId": 1,
                      "quantity": 121,
                      "receiverName": "郭克",
                      "receiverPhone": "13800138000",
                      "shippingAddress": "北京市朝阳区测试路 1 号"
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.message").value("商品库存不足或已下架"))
            .andExpect(jsonPath("$.data").value(nullValue()));

        assertEquals(120, productMapper.selectById(1L).getStock());
        assertEquals(orderCountBefore, orderMapper.selectCount(null));
    }

    @Test
    @DisplayName("下架商品不能下单")
    void should_returnConflict_when_productOffSale() throws Exception {
        mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "productId": 10,
                      "quantity": 1,
                      "receiverName": "郭克",
                      "receiverPhone": "13800138000",
                      "shippingAddress": "北京市朝阳区测试路 1 号"
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("商品已下架"))
            .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("不存在的商品不能下单")
    void should_returnNotFound_when_productMissing() throws Exception {
        mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "productId": 999999,
                      "quantity": 1,
                      "receiverName": "郭克",
                      "receiverPhone": "13800138000",
                      "shippingAddress": "北京市朝阳区测试路 1 号"
                    }
                    """))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("商品不存在"))
            .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("购买数量非法时返回 400")
    void should_returnBadRequest_when_quantityInvalid() throws Exception {
        mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "productId": 1,
                      "quantity": 0,
                      "receiverName": "郭克",
                      "receiverPhone": "13800138000",
                      "shippingAddress": "北京市朝阳区测试路 1 号"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("quantity")))
            .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("不传过滤条件时分页查询返回全部种子订单")
    void should_listAllOrders_when_noFiltersProvided() throws Exception {
        mockMvc.perform(get("/api/orders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.total").value(4))
            .andExpect(jsonPath("$.data.page").value(1))
            .andExpect(jsonPath("$.data.size").value(20))
            .andExpect(jsonPath("$.data.records", hasSize(4)));
    }

    @Test
    @DisplayName("按订单状态和业务订单号过滤并正确翻页")
    void should_filterAndPaginateOrders() throws Exception {
        mockMvc.perform(get("/api/orders").param("status", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].orderNo").value("SO202611240002"))
            .andExpect(jsonPath("$.data.records[0].status").value(1));

        mockMvc.perform(get("/api/orders").param("orderNo", "SO202611240003"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].id").value(3))
            .andExpect(jsonPath("$.data.records[0].statusText").value("已发货"));

        mockMvc.perform(get("/api/orders").param("page", "2").param("size", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(4))
            .andExpect(jsonPath("$.data.page").value(2))
            .andExpect(jsonPath("$.data.size").value(2))
            .andExpect(jsonPath("$.data.records", hasSize(2)));
    }

    @Test
    @DisplayName("查询订单详情：存在返回 200，不存在返回 404，非法 ID 返回 400")
    void should_getOrderDetail_orReturnError() throws Exception {
        mockMvc.perform(get("/api/orders/2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.id").value(2))
            .andExpect(jsonPath("$.data.orderNo").value("SO202611240002"))
            .andExpect(jsonPath("$.data.totalAmountCents").value(89900))
            .andExpect(jsonPath("$.data.status").value(1));

        mockMvc.perform(get("/api/orders/999999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.message").value("订单不存在"));

        mockMvc.perform(get("/api/orders/abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("订单列表状态值或分页参数非法时返回 400")
    void should_returnBadRequest_when_orderQueryInvalid() throws Exception {
        mockMvc.perform(get("/api/orders").param("status", "5"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get("/api/orders").param("page", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }
}
