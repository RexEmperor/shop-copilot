package com.shopcopilot.business.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopcopilot.business.aftersale.mapper.AftersaleTicketMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 真实 Streamable HTTP MCP 协议端到端集成测试：
 * 验证 initialize -> notifications/initialized -> tools/list -> tools/call 全链路。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AftersaleTicketMapper aftersaleTicketMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    @DisplayName("Streamable HTTP MCP 协议握手、枚举 6 个业务工具并真实调用商品/订单/售后")
    void should_handshakeListTools_andInvokeBusinessToolsOverMcp() throws Exception {
        String sessionId = initializeSession();
        sendInitializedNotification(sessionId);

        // 1. 枚举工具列表
        JsonNode listResult = sendJsonRpc(sessionId, Map.of(
            "jsonrpc", "2.0",
            "id", 2,
            "method", "tools/list",
            "params", Map.of()
        ));
        Set<String> toolNames = new HashSet<>();
        for (JsonNode tool : listResult.path("result").path("tools")) {
            toolNames.add(tool.path("name").asText());
        }
        assertEquals(Set.of(
            "list_categories",
            "search_products",
            "get_product_detail",
            "list_orders",
            "get_order_detail",
            "create_aftersale",
            "list_aftersales"
        ), toolNames);

        // 1.5 调用 list_categories 验证类目统计走真实数据（含 4 个数码，不含下架台灯）
        JsonNode categoriesPayload = callTool(sessionId, 21, "list_categories", Map.of());
        assertTrue(categoriesPayload.path("success").asBoolean());
        assertEquals(4, categoriesPayload.path("data").size());
        assertEquals("数码", categoriesPayload.path("data").get(0).path("category").asText());
        assertEquals(4, categoriesPayload.path("data").get(0).path("productCount").asInt());

        // 2. 调用 search_products（按类目过滤且不含下架商品）
        JsonNode searchPayload = callTool(sessionId, 3, "search_products", Map.of("category", "数码"));
        assertTrue(searchPayload.path("success").asBoolean());
        assertEquals(4, searchPayload.path("data").path("total").asInt());
        assertTrue(searchPayload.path("data").path("availableCategories").isMissingNode());

        // 2.5 调用 search_products 零结果时附带 availableCategories 供模型纠偏
        JsonNode emptyPayload = callTool(sessionId, 31, "search_products", Map.of("keyword", "数码"));
        assertTrue(emptyPayload.path("success").asBoolean());
        assertEquals(0, emptyPayload.path("data").path("total").asInt());
        assertEquals(4, emptyPayload.path("data").path("availableCategories").size());
        assertEquals("数码", emptyPayload.path("data").path("availableCategories").get(0).asText());

        // 3. 调用 get_product_detail 查下架商品 #10 -> 404 结构化拦截
        JsonNode offSalePayload = callTool(sessionId, 4, "get_product_detail", Map.of("productId", 10));
        assertFalse(offSalePayload.path("success").asBoolean());
        assertEquals(404, offSalePayload.path("code").asInt());
        assertEquals("商品已下架", offSalePayload.path("message").asText());

        // 4. 调用 list_orders 与 get_order_detail
        JsonNode ordersPayload = callTool(sessionId, 5, "list_orders", Map.of("orderNo", "SO202611240002"));
        assertTrue(ordersPayload.path("success").asBoolean());
        assertEquals("899.00", ordersPayload.path("data").path("records").get(0).path("totalAmountYuan").asText());

        JsonNode orderDetailPayload = callTool(sessionId, 6, "get_order_detail", Map.of("orderId", 3));
        assertTrue(orderDetailPayload.path("success").asBoolean());
        assertEquals("已发货", orderDetailPayload.path("data").path("statusText").asText());
        assertFalse(orderDetailPayload.path("data").path("shippedAt").isNull());

        // 5. 调用 list_aftersales 查种子售后单
        JsonNode aftersalesPayload = callTool(sessionId, 7, "list_aftersales", Map.of("orderNo", "SO202611240002"));
        assertTrue(aftersalesPayload.path("success").asBoolean());
        assertEquals("AS202611240001", aftersalesPayload.path("data").path("records").get(0).path("ticketNo").asText());

        // 6. 调用 create_aftersale：待支付订单被 Java 业务状态机拒绝 (409)
        JsonNode rejectPayload = callTool(sessionId, 8, "create_aftersale", Map.of(
            "orderNo", "SO202611240001",
            "type", 0,
            "reason", "还没付款测试退款"
        ));
        assertFalse(rejectPayload.path("success").asBoolean());
        assertEquals(409, rejectPayload.path("code").asInt());
        assertEquals("当前订单状态不允许发起售后", rejectPayload.path("message").asText());

        // 7. 调用 create_aftersale：合法已支付订单创建成功并清理测试工单
        Long createdTicketId = null;
        try {
            JsonNode createdPayload = callTool(sessionId, 9, "create_aftersale", Map.of(
                "orderNo", "SO202611240002",
                "type", 0,
                "reason", "MCP 端到端联调申请仅退款"
            ));
            assertTrue(createdPayload.path("success").asBoolean());
            assertEquals(89900L, createdPayload.path("data").path("refundCents").asLong());
            assertEquals("899.00", createdPayload.path("data").path("refundYuan").asText());
            assertEquals("待审核", createdPayload.path("data").path("statusText").asText());
            createdTicketId = createdPayload.path("data").path("id").asLong();
        } finally {
            if (createdTicketId != null) {
                aftersaleTicketMapper.deleteById(createdTicketId);
            }
        }
    }

    private String initializeSession() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "jsonrpc", "2.0",
            "id", 1,
            "method", "initialize",
            "params", Map.of(
                "protocolVersion", "2025-03-26",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "mcp-integration-test", "version", "1.0.0")
            )
        ));
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "initialize failed: " + response.body());
        String sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        assertNotNull(sessionId, "Expected Mcp-Session-Id header");

        JsonNode json = parseMcpBody(response.body());
        assertEquals("shop-copilot-business-mcp", json.path("result").path("serverInfo").path("name").asText());
        return sessionId;
    }

    private void sendInitializedNotification(String sessionId) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "jsonrpc", "2.0",
            "method", "notifications/initialized"
        ));
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Mcp-Session-Id", sessionId)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
            "notifications/initialized failed: " + response.statusCode() + " " + response.body());
    }

    private JsonNode callTool(String sessionId, int id, String name, Map<String, Object> arguments)
            throws Exception {
        JsonNode rpc = sendJsonRpc(sessionId, Map.of(
            "jsonrpc", "2.0",
            "id", id,
            "method", "tools/call",
            "params", Map.of(
                "name", name,
                "arguments", arguments
            )
        ));
        JsonNode content = rpc.path("result").path("content");
        assertTrue(content.isArray() && !content.isEmpty(), "Empty MCP tool content: " + rpc);
        String text = content.get(0).path("text").asText();
        return objectMapper.readTree(text);
    }

    private JsonNode sendJsonRpc(String sessionId, Map<String, Object> payload) throws Exception {
        String body = objectMapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Mcp-Session-Id", sessionId)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "MCP JSON-RPC failed: " + response.body());
        return parseMcpBody(response.body());
    }

    private JsonNode parseMcpBody(String rawBody) throws Exception {
        String trimmed = rawBody.trim();
        if (trimmed.startsWith("{")) {
            return objectMapper.readTree(trimmed);
        }
        String lastData = null;
        for (String line : trimmed.split("\n")) {
            String stripped = line.trim();
            if (stripped.startsWith("data:")) {
                lastData = stripped.substring(5).trim();
            }
        }
        assertNotNull(lastData, "Unable to parse MCP response: " + rawBody);
        return objectMapper.readTree(lastData);
    }
}
