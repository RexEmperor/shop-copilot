package com.shopcopilot.business.mcp;

import java.math.BigDecimal;
import org.springframework.http.HttpStatus;

/**
 * MCP 工具统一返回结构，保证业务校验失败与异常均转为结构化结果，不向模型泄露堆栈。
 */
public record McpToolResponse<T>(
    boolean success,
    int code,
    String message,
    T data
) {

    public static <T> McpToolResponse<T> ok(T data) {
        return new McpToolResponse<>(true, HttpStatus.OK.value(), "success", data);
    }

    public static <T> McpToolResponse<T> fail(HttpStatus status, String message) {
        return new McpToolResponse<>(false, status.value(), message, null);
    }

    public static String centsToYuan(Long cents) {
        if (cents == null) {
            return null;
        }
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }
}
