package com.shopcopilot.business.mcp;

import com.shopcopilot.business.common.BusinessException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

/**
 * MCP 工具公共能力：统一异常兜底、分页边界裁剪与通用参数校验。
 */
public final class McpToolSupport {

    private static final Logger log = LoggerFactory.getLogger(McpToolSupport.class);
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 20;

    private McpToolSupport() {
    }

    public static <T> McpToolResponse<T> executeSafely(String toolName, Supplier<T> action) {
        try {
            return McpToolResponse.ok(action.get());
        } catch (BusinessException exception) {
            log.info("MCP tool business reject: tool={}, status={}, msg={}",
                toolName, exception.getStatus().value(), exception.getMessage());
            return McpToolResponse.fail(exception.getStatus(), exception.getMessage());
        } catch (IllegalArgumentException exception) {
            log.info("MCP tool invalid argument: tool={}, msg={}", toolName, exception.getMessage());
            return McpToolResponse.fail(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (Exception exception) {
            log.error("MCP tool unexpected error: tool={}", toolName, exception);
            return McpToolResponse.fail(
                HttpStatus.INTERNAL_SERVER_ERROR, "业务服务暂时不可用，请稍后重试或转人工客服");
        }
    }

    public static int normalizePage(Integer page) {
        return (page == null || page < 1) ? DEFAULT_PAGE : page;
    }

    public static int normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    public static void requirePositiveId(Long id, String label) {
        if (id == null || id <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, label + "必须为正整数");
        }
    }

    public static void validateOptionalOrderNo(String orderNo) {
        if (StringUtils.hasText(orderNo) && orderNo.trim().length() > 32) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "订单号不能超过32个字符");
        }
    }

    public static void validateOptionalRange(Integer value, int min, int max, String label) {
        if (value != null && (value < min || value > max)) {
            throw new BusinessException(
                HttpStatus.BAD_REQUEST, label + "必须在 " + min + " 到 " + max + " 之间");
        }
    }
}
