"""MCP Client 桥接层：通过 Streamable HTTP 连接 Java business-api MCP Server。

核心职责：
1. 动态拉取 Java MCP Server 工具定义并转换为 OpenAI function calling schema；
2. 支持按 allowed_names 白名单裁剪工具集合（供 S3 多 Agent 最小权限绑定）；
3. 幂等读工具遇瞬时传输错误自动重试，非幂等写工具（如 create_aftersale）禁止盲目重试；
4. MCP Server 不可达时 fail-open 降级，不阻塞 agent-runtime 启动与兜底对话。
"""
import asyncio
from contextlib import asynccontextmanager
import json
import logging
import time
from typing import Any

import httpx
from langfuse import observe
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

from app import config

logger = logging.getLogger("mcp_client")

# 写操作不自动重试，防止超时后服务端已落库造成重复提交或语义混乱
NON_IDEMPOTENT_TOOLS = frozenset({"create_aftersale"})


def mcp_tool_to_openai_schema(tool: Any) -> dict:
    """将 MCP Tool 对象转换为 DeepSeek / OpenAI 兼容的 function tool schema。
    兼容 mcp 2.x（input_schema）与 1.x（inputSchema）字段命名。
    """
    raw_schema = (
        getattr(tool, "input_schema", None)
        or getattr(tool, "inputSchema", None)
        or {"type": "object", "properties": {}}
    )
    return {
        "type": "function",
        "function": {
            "name": tool.name,
            "description": getattr(tool, "description", None) or "",
            "parameters": raw_schema,
        },
    }


def extract_tool_result_text(result: Any) -> str:
    """提取 MCP CallToolResult 中的文本内容（兼容 is_error / isError）。"""
    content = getattr(result, "content", None) or []
    parts: list[str] = []
    for block in content:
        text = getattr(block, "text", None)
        if text is not None:
            parts.append(text)
        elif hasattr(block, "model_dump"):
            parts.append(json.dumps(block.model_dump(), ensure_ascii=False))
        else:
            parts.append(str(block))
    text = "\n".join(parts).strip()
    is_error = bool(getattr(result, "is_error", False) or getattr(result, "isError", False))
    if is_error:
        return f"外部业务工具返回错误: {text or '未知错误'}"
    return text or "{}"


class McpClientManager:
    def __init__(
        self,
        url: str | None = None,
        *,
        enabled: bool | None = None,
        timeout_seconds: float | None = None,
        max_retries: int | None = None,
        cache_ttl_seconds: float | None = None,
        clock=time.monotonic,
        session_factory=None,
    ):
        self.url = url or config.MCP_SERVER_URL
        self.enabled = config.MCP_ENABLED if enabled is None else enabled
        self.timeout_seconds = (
            config.MCP_TIMEOUT_SECONDS if timeout_seconds is None else timeout_seconds
        )
        self.max_retries = config.MCP_MAX_RETRIES if max_retries is None else max_retries
        self.cache_ttl_seconds = (
            config.MCP_TOOLS_CACHE_TTL_SECONDS
            if cache_ttl_seconds is None
            else cache_ttl_seconds
        )
        self._clock = clock
        self._session_factory = session_factory or self._default_session
        self._tools_cache: dict[str, dict] | None = None
        self._cache_loaded_at: float = 0.0
        self._cache_lock = asyncio.Lock()

    @asynccontextmanager
    async def _default_session(self):
        timeout = httpx.Timeout(self.timeout_seconds, read=self.timeout_seconds)
        async with httpx.AsyncClient(timeout=timeout) as http_client:
            async with streamable_http_client(self.url, http_client=http_client) as (
                read_stream,
                write_stream,
            ):
                async with ClientSession(read_stream, write_stream) as session:
                    await session.initialize()
                    yield session

    def invalidate_cache(self) -> None:
        self._tools_cache = None
        self._cache_loaded_at = 0.0

    def _is_cache_valid(self) -> bool:
        if self._tools_cache is None:
            return False
        if self.cache_ttl_seconds <= 0:
            return False
        return (self._clock() - self._cache_loaded_at) < self.cache_ttl_seconds

    async def list_tools(self, *, force_refresh: bool = False) -> dict[str, dict]:
        """返回 {tool_name: openai_schema}，带 TTL 内存缓存与双重检查锁；
        TTL 过期后刷新失败时优先兜底返回上一次有效缓存（stale fallback），首次失败则 fail-open 返回空字典。
        """
        if not self.enabled:
            return {}
        if not force_refresh and self._is_cache_valid():
            return dict(self._tools_cache or {})

        async with self._cache_lock:
            if not force_refresh and self._is_cache_valid():
                return dict(self._tools_cache or {})
            try:
                async with self._session_factory() as session:
                    response = await session.list_tools()
                tools = {
                    t.name: mcp_tool_to_openai_schema(t)
                    for t in (getattr(response, "tools", None) or [])
                }
                self._tools_cache = tools
                self._cache_loaded_at = self._clock()
                logger.info("MCP tools loaded from %s: %s", self.url, sorted(tools))
                return dict(tools)
            except Exception as exc:
                if self._tools_cache is not None:
                    logger.warning(
                        "MCP list_tools refresh failed (%s), serving stale cache (%s tools): %r",
                        self.url, len(self._tools_cache), exc,
                    )
                    return dict(self._tools_cache)
                logger.warning("MCP list_tools failed (%s), fail-open with empty tools: %r",
                               self.url, exc)
                return {}

    async def get_schemas(self, allowed_names: set[str] | None = None) -> list[dict]:
        """返回 OpenAI tools schema 列表；可通过 allowed_names 按 Agent 角色白名单过滤。"""
        tools = await self.list_tools()
        if allowed_names is None:
            return list(tools.values())
        return [schema for name, schema in tools.items() if name in allowed_names]

    async def has_tool(self, name: str) -> bool:
        if not self.enabled:
            return False
        tools = await self.list_tools()
        return name in tools

    @observe(name="mcp-call-tool")
    async def call_tool(self, name: str, arguments: dict | None = None) -> str:
        """通过 MCP 协议调用远端业务工具，带超时与幂等安全重试。"""
        if not self.enabled:
            return f"外部业务服务未启用，无法调用工具 {name}，请转人工客服。"

        args = arguments or {}
        attempts = 1 if name in NON_IDEMPOTENT_TOOLS else (1 + max(0, self.max_retries))
        last_err: Exception | None = None

        for attempt in range(1, attempts + 1):
            try:
                async with self._session_factory() as session:
                    result = await session.call_tool(
                        name,
                        arguments=args,
                        read_timeout_seconds=self.timeout_seconds,
                    )
                return extract_tool_result_text(result)
            except Exception as exc:
                last_err = exc
                logger.warning(
                    "MCP call_tool failed: tool=%s attempt=%s/%s err=%r",
                    name, attempt, attempts, exc,
                )
                if attempt < attempts:
                    await asyncio.sleep(0.3 * (2 ** (attempt - 1)))

        return (
            f"外部业务工具 {name} 调用失败（{type(last_err).__name__}），"
            "请稍后重试或转人工客服协助处理。"
        )


mcp_client = McpClientManager()
