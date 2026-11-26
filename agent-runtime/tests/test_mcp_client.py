"""MCP Client 桥接与工具路由单元测试（无外部网络依赖）。"""
from contextlib import asynccontextmanager
from types import SimpleNamespace

from app import tools
from app.mcp_client import McpClientManager, extract_tool_result_text, mcp_tool_to_openai_schema


def _fake_tool(name: str, description: str, properties: dict, required: list[str] | None = None):
    return SimpleNamespace(
        name=name,
        description=description,
        inputSchema={
            "type": "object",
            "properties": properties,
            "required": required or [],
        },
    )


def _fake_result(text: str, *, is_error: bool = False):
    return SimpleNamespace(
        content=[SimpleNamespace(text=text)],
        isError=is_error,
    )


def test_mcp_tool_to_openai_schema_conversion():
    t = _fake_tool("search_products", "搜索商品", {"keyword": {"type": "string"}}, ["keyword"])
    converted = mcp_tool_to_openai_schema(t)
    assert converted == {
        "type": "function",
        "function": {
            "name": "search_products",
            "description": "搜索商品",
            "parameters": {
                "type": "object",
                "properties": {"keyword": {"type": "string"}},
                "required": ["keyword"],
            },
        },
    }


def test_extract_tool_result_text_handles_error_flag():
    assert extract_tool_result_text(_fake_result('{"ok":true}')) == '{"ok":true}'
    err_text = extract_tool_result_text(_fake_result("boom", is_error=True))
    assert "错误" in err_text and "boom" in err_text


async def test_list_tools_caches_and_filters_by_allowed_names():
    list_calls = 0

    class FakeSession:
        async def list_tools(self):
            nonlocal list_calls
            list_calls += 1
            return SimpleNamespace(
                tools=[
                    _fake_tool("search_products", "搜索商品", {}),
                    _fake_tool("create_aftersale", "发起售后", {}),
                ]
            )

    @asynccontextmanager
    async def factory():
        yield FakeSession()

    client = McpClientManager(url="http://fake/mcp", enabled=True, session_factory=factory)

    all_tools = await client.get_schemas()
    assert len(all_tools) == 2
    assert list_calls == 1

    # 二次调用命中缓存，并按角色白名单裁剪
    guide_only = await client.get_schemas(allowed_names={"search_products"})
    assert [s["function"]["name"] for s in guide_only] == ["search_products"]
    assert list_calls == 1


async def test_call_tool_retries_idempotent_read_but_not_write():
    attempts = {"search_products": 0, "create_aftersale": 0}

    class FlakySession:
        async def call_tool(self, name, arguments=None, read_timeout_seconds=None):
            attempts[name] += 1
            if name == "search_products" and attempts[name] == 1:
                raise TimeoutError("transient timeout")
            if name == "create_aftersale":
                raise TimeoutError("write timeout")
            return _fake_result('{"success":true,"data":{"total":1}}')

    @asynccontextmanager
    async def factory():
        yield FlakySession()

    client = McpClientManager(
        url="http://fake/mcp", enabled=True, max_retries=1, session_factory=factory
    )

    read_out = await client.call_tool("search_products", {"category": "数码"})
    assert '"success":true' in read_out
    assert attempts["search_products"] == 2  # 幂等读工具自动重试一次后成功

    write_out = await client.call_tool("create_aftersale", {"orderNo": "SO1", "type": 0, "reason": "x"})
    assert "调用失败" in write_out and "人工客服" in write_out
    assert attempts["create_aftersale"] == 1  # 非幂等写工具禁止盲目重试


async def test_list_tools_fails_open_when_server_unreachable():
    @asynccontextmanager
    async def broken_factory():
        raise ConnectionRefusedError("down")
        yield  # pragma: no cover

    client = McpClientManager(url="http://fake/mcp", enabled=True, session_factory=broken_factory)
    assert await client.list_tools() == {}


async def test_tools_execute_routes_remote_mcp_tool(monkeypatch):
    class FakeMcp:
        enabled = True

        async def has_tool(self, name: str) -> bool:
            return name == "get_product_detail"

        async def call_tool(self, name: str, arguments: dict) -> str:
            return f"mcp:{name}:{arguments.get('productId')}"

        async def get_schemas(self, allowed_names=None):
            schema = {"type": "function", "function": {"name": "get_product_detail", "parameters": {}}}
            if allowed_names is not None and "get_product_detail" not in allowed_names:
                return []
            return [schema]

    monkeypatch.setattr(tools, "mcp_client", FakeMcp())

    out = await tools.execute("get_product_detail", {"productId": 1}, {})
    assert out == "mcp:get_product_detail:1"

    merged = await tools.all_schemas(allowed_names={"get_product_detail"})
    assert [s["function"]["name"] for s in merged] == ["get_product_detail"]
