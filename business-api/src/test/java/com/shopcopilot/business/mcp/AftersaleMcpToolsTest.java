package com.shopcopilot.business.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shopcopilot.business.aftersale.dto.AftersaleCreateRequest;
import com.shopcopilot.business.aftersale.service.AftersaleService;
import com.shopcopilot.business.aftersale.vo.AftersaleVO;
import com.shopcopilot.business.mcp.McpModels.McpAftersaleItem;
import com.shopcopilot.business.mcp.McpModels.McpPageData;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AftersaleMcpToolsTest {

    @Mock
    private AftersaleService aftersaleService;

    private AftersaleMcpTools tools;

    @BeforeEach
    void setUp() {
        tools = new AftersaleMcpTools(aftersaleService);
    }

    @Test
    @DisplayName("create_aftersale 与 list_aftersales 校验入参并返回后端派生的退款金额")
    void should_validateAndCreateAftersale() {
        McpToolResponse<McpAftersaleItem> badType = tools.createAftersale("SO202611240002", 2, "原因");
        assertFalse(badType.success());
        assertEquals(400, badType.code());

        McpToolResponse<McpPageData<McpAftersaleItem>> badStatus = tools.listAftersales(null, 5, 1, 10);
        assertFalse(badStatus.success());
        assertEquals(400, badStatus.code());
        verifyNoInteractions(aftersaleService);

        LocalDateTime now = LocalDateTime.of(2026, 11, 26, 12, 0, 0);
        when(aftersaleService.create(any(AftersaleCreateRequest.class))).thenReturn(11L);
        when(aftersaleService.getById(11L)).thenReturn(new AftersaleVO(
            11L, "AS123456", "SO202611240002", 10001L, 0, 0, "待审核",
            89900L, "不想要了", null, null, null, now, now));

        McpToolResponse<McpAftersaleItem> resp = tools.createAftersale(" SO202611240002 ", 0, " 不想要了 ");
        assertTrue(resp.success());
        assertEquals("899.00", resp.data().refundYuan());
        assertEquals("仅退款", resp.data().typeText());
        assertEquals("待审核", resp.data().statusText());
    }
}
