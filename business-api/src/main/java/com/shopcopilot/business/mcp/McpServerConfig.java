package com.shopcopilot.business.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按业务领域注册 MCP 工具组件；新增领域（如售前规则/营销等）只需追加独立 Tool Bean。
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider shopMcpToolCallbackProvider(
            ProductMcpTools productMcpTools,
            OrderMcpTools orderMcpTools,
            AftersaleMcpTools aftersaleMcpTools) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(productMcpTools, orderMcpTools, aftersaleMcpTools)
            .build();
    }
}
