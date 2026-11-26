package com.shopcopilot.business.product;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProductApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    @AfterEach
    void clearProductCacheKeys() {
        Set<String> keys = stringRedisTemplate.keys("shop:product:detail:*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    @Test
    void should_listOnlyOnSaleProducts_byDefault() throws Exception {
        mockMvc.perform(get("/api/products"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.total").value(9))
            .andExpect(jsonPath("$.data.records", hasSize(9)))
            .andExpect(jsonPath("$.data.records[*].id", not(contains(10))));
    }

    @Test
    void should_filterOffSaleProducts_when_statusIsZero() throws Exception {
        mockMvc.perform(get("/api/products").param("status", "0"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].id").value(10))
            .andExpect(jsonPath("$.data.records[0].status").value(0));
    }

    @Test
    void should_filterByKeywordAndCategory() throws Exception {
        mockMvc.perform(get("/api/products")
                .param("keyword", "耳机")
                .param("category", "数码"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].id").value(1))
            .andExpect(jsonPath("$.data.records[0].name").value("无线蓝牙耳机 Pro"));
    }

    @Test
    void should_returnProductDetail() throws Exception {
        mockMvc.perform(get("/api/products/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(1))
            .andExpect(jsonPath("$.data.priceCents").value(19900))
            .andExpect(jsonPath("$.data.stock").value(120))
            .andExpect(jsonPath("$.data.status").value(1));
    }

    @Test
    void should_createProduct_withOnSaleStatus() throws Exception {
        String request = """
            {
              "name": "测试商品",
              "category": "测试",
              "description": "用于商品模块集成测试",
              "priceCents": 12345,
              "stock": 10
            }
            """;

        mockMvc.perform(post("/api/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data").isNumber())
            .andDo(result -> {
                long id = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").asLong();
                mockMvc.perform(get("/api/products/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("测试商品"))
                    .andExpect(jsonPath("$.data.status").value(1))
                    .andExpect(jsonPath("$.data.version").value(0));
            });
    }

    @Test
    void should_updateProduct_andIncrementVersion() throws Exception {
        String request = """
            {
              "name": "无线蓝牙耳机 Pro Max",
              "category": "数码",
              "description": "更新后的商品描述",
              "priceCents": 21900,
              "stock": 180
            }
            """;

        mockMvc.perform(put("/api/products/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/products/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("无线蓝牙耳机 Pro Max"))
            .andExpect(jsonPath("$.data.priceCents").value(21900))
            .andExpect(jsonPath("$.data.stock").value(180))
            .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    void should_changeProductStatus_andAffectDefaultList() throws Exception {
        mockMvc.perform(put("/api/products/1/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/products"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(8))
            .andExpect(jsonPath("$.data.records[*].id", not(contains(1))));

        mockMvc.perform(get("/api/products").param("status", "0"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    void should_return400_whenCreateRequestInvalid() throws Exception {
        String request = """
            {
              "name": " ",
              "category": "测试",
              "description": "非法参数",
              "priceCents": -1,
              "stock": -1
            }
            """;

        mockMvc.perform(post("/api/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void should_return404_whenProductMissing() throws Exception {
        mockMvc.perform(get("/api/products/99999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.message").value("商品不存在"));
    }

    @Test
    void should_return404_whenProductOffSale() throws Exception {
        mockMvc.perform(get("/api/products/10"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.message").value("商品已下架"));
    }

    @Test
    void should_returnPaginationMetadata() throws Exception {
        mockMvc.perform(get("/api/products").param("page", "2").param("size", "3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(9))
            .andExpect(jsonPath("$.data.page").value(2))
            .andExpect(jsonPath("$.data.size").value(3))
            .andExpect(jsonPath("$.data.records", hasSize(3)));
    }

    @Test
    void should_returnEmptyRecords_whenPageBeyondLastPage() throws Exception {
        mockMvc.perform(get("/api/products").param("page", "99").param("size", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(9))
            .andExpect(jsonPath("$.data.page").value(99))
            .andExpect(jsonPath("$.data.records", hasSize(0)));
    }

    @Test
    void should_return400_whenStatusOutOfRange() throws Exception {
        mockMvc.perform(get("/api/products").param("status", "2"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void should_return400_whenStatusIsNotNumber() throws Exception {
        mockMvc.perform(get("/api/products").param("status", "abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void should_return400_whenPageIsNotNumber() throws Exception {
        mockMvc.perform(get("/api/products").param("page", "abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void should_return400_whenProductIdIsNotNumber() throws Exception {
        mockMvc.perform(get("/api/products/abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }
}
