"""LangGraph 多 Agent 协作状态机单元测试与剧本集成测试（无外部 LLM/网络依赖）。"""
import json

from app.graph import workers, workflow
from app.graph.router import parse_route_decision
from app.graph.workers import inject_category_enum, is_tool_system_failure


def _mock_schemas():
    names = [
        "list_categories",
        "search_products",
        "get_product_detail",
        "list_orders",
        "get_order_detail",
        "create_aftersale",
        "list_aftersales",
    ]
    result = []
    for name in names:
        props = (
            {"category": {"type": "string", "description": "商品分类"}, "keyword": {"type": "string"}}
            if name == "search_products"
            else {}
        )
        result.append({
            "type": "function",
            "function": {
                "name": name,
                "description": name,
                "parameters": {"type": "object", "properties": props},
            },
        })
    return result


def _llm_reply(content: str, *, tool_calls=None, tokens: int = 10):
    raw_msg = {"role": "assistant", "content": content}
    if tool_calls:
        raw_msg["tool_calls"] = [
            {
                "id": tc["id"],
                "type": "function",
                "function": {"name": tc["name"], "arguments": json.dumps(tc["args"])},
            }
            for tc in tool_calls
        ]
    return {
        "content": content,
        "raw_assistant_message": raw_msg,
        "tool_calls": tool_calls or [],
        "model": "deepseek-chat",
        "elapsed_s": 0.01,
        "usage": {"prompt_tokens": tokens, "completion_tokens": 5, "total_tokens": tokens + 5},
    }


def test_parse_route_decision_cases():
    assert parse_route_decision('{"route":"guide","plan":["guide"],"reason":"选品"}') == (
        "guide", ["guide"], "选品"
    )
    # 剥离 Markdown 代码块并限制跨域最多 2 步且去重
    wrapped = '```json\n{"route":"order_aftersale","plan":["order_aftersale","guide","guide","product_qa"],"reason":"复合"}\n```'
    assert parse_route_decision(wrapped) == (
        "order_aftersale", ["order_aftersale", "guide"], "复合"
    )
    assert parse_route_decision('{"route":"chitchat","plan":["guide"],"reason":"打招呼"}') == (
        "chitchat", [], "打招呼"
    )
    assert parse_route_decision("not json at all")[0] == "guide"


def test_inject_category_enum_and_failure_classification():
    base = _mock_schemas()
    enriched = inject_category_enum(base, ["数码", "服饰"])
    sp = next(s for s in enriched if s["function"]["name"] == "search_products")
    assert sp["function"]["parameters"]["properties"]["category"]["enum"] == ["数码", "服饰"]
    # 原始 schema 未被原地污染
    orig_sp = next(s for s in base if s["function"]["name"] == "search_products")
    assert "enum" not in orig_sp["function"]["parameters"]["properties"]["category"]

    assert is_tool_system_failure('{"success":false,"code":404,"message":"商品已下架"}') is False
    assert is_tool_system_failure('{"success":false,"code":409,"message":"状态不允许"}') is False
    assert is_tool_system_failure('{"success":false,"code":500,"message":"服务异常"}') is True
    assert is_tool_system_failure("外部业务工具 search_products 调用失败（TimeoutError）") is True


async def test_single_domain_guide_least_privilege_and_enum_injection(session_id, monkeypatch):
    """单域导购：验证路由无工具、导购仅获商品工具+动态类目enum、且不走多余汇总节点。"""
    workers.invalidate_category_cache()
    all_mock = _mock_schemas()

    async def fake_all_schemas(*, allowed_names=None, include_local=True, include_mcp=True):
        if allowed_names is None:
            return all_mock
        return [s for s in all_mock if s["function"]["name"] in allowed_names]

    async def fake_categories():
        return ["数码", "服饰"]

    async def fake_execute(name, args, context):
        assert name == "search_products"
        assert args == {"category": "数码"}
        return '{"success":true,"code":200,"data":{"records":[{"name":"无线蓝牙耳机 Pro","priceYuan":"199.00"}],"total":1}}'

    monkeypatch.setattr(workers, "all_schemas", fake_all_schemas)
    monkeypatch.setattr(workers, "fetch_on_sale_categories", fake_categories)
    monkeypatch.setattr(workers, "execute", fake_execute)

    recorded_tools = []
    step = 0

    class FakeLLM:
        model = "deepseek-chat"

        async def chat(self, messages, tools=None):
            nonlocal step
            step += 1
            recorded_tools.append(tools)
            if step == 1:
                return _llm_reply('{"route":"guide","plan":["guide"],"reason":"选数码"}', tokens=20)
            if step == 2:
                return _llm_reply(
                    "",
                    tool_calls=[{"id": "c1", "name": "search_products", "args": {"category": "数码"}}],
                    tokens=30,
                )
            return _llm_reply("推荐无线蓝牙耳机 Pro，价格 199.00 元。", tokens=40)

    res = await workflow.run_graph(session_id, "有什么电子产品？", llm_client=FakeLLM())

    assert res["route"] == "guide"
    assert res["agents_used"] == ["guide"]
    assert res["tool_rounds"] == 1
    assert res["handoff_requested"] is False
    assert "199.00" in res["content"]
    assert step == 3  # router + guide(tool) + guide(answer)，未调用 synthesize
    assert res["usage"]["prompt_tokens"] == 90

    # 校验最小权限与动态 enum 注入
    assert recorded_tools[0] is None
    guide_tool_names = {t["function"]["name"] for t in recorded_tools[1]}
    assert guide_tool_names == {"list_categories", "search_products", "get_product_detail"}
    assert "create_aftersale" not in guide_tool_names
    sp = next(t for t in recorded_tools[1] if t["function"]["name"] == "search_products")
    assert sp["function"]["parameters"]["properties"]["category"]["enum"] == ["数码", "服饰"]


async def test_chitchat_uses_zero_tool_schemas(session_id):
    recorded_tools = []
    step = 0

    class FakeLLM:
        model = "deepseek-chat"

        async def chat(self, messages, tools=None):
            nonlocal step
            step += 1
            recorded_tools.append(tools)
            if step == 1:
                return _llm_reply('{"route":"chitchat","plan":[],"reason":"问候"}')
            return _llm_reply("你好！有什么商品或订单需要帮忙吗？")

    res = await workflow.run_graph(session_id, "你好呀", llm_client=FakeLLM())

    assert res["route"] == "chitchat"
    assert res["tool_rounds"] == 0
    assert recorded_tools == [None, None]


async def test_cross_domain_collaboration_order_then_guide_and_synthesize(session_id, monkeypatch):
    """跨域复合请求：order_aftersale -> guide 接力协作 -> synthesize 汇总。"""
    all_mock = _mock_schemas()

    async def fake_all_schemas(*, allowed_names=None, include_local=True, include_mcp=True):
        return [s for s in all_mock if s["function"]["name"] in (allowed_names or set())]

    async def fake_categories():
        return ["数码"]

    async def fake_execute(name, args, context):
        if name == "list_orders":
            return '{"success":true,"code":200,"data":{"records":[{"orderNo":"SO202611240002","statusText":"已支付"}]}}'
        return '{"success":true,"code":200,"data":{"records":[{"name":"无线蓝牙耳机 Pro","priceYuan":"199.00"}]}}'

    monkeypatch.setattr(workers, "all_schemas", fake_all_schemas)
    monkeypatch.setattr(workers, "fetch_on_sale_categories", fake_categories)
    monkeypatch.setattr(workers, "execute", fake_execute)

    tool_sets_seen = []
    step = 0

    class FakeLLM:
        model = "deepseek-chat"

        async def chat(self, messages, tools=None):
            nonlocal step
            step += 1
            tool_sets_seen.append(None if tools is None else {t["function"]["name"] for t in tools})
            if step == 1:  # router
                return _llm_reply('{"route":"order_aftersale","plan":["order_aftersale","guide"],"reason":"查单+推荐"}')
            if step == 2:  # order_aftersale calls list_orders
                return _llm_reply("", tool_calls=[{"id": "o1", "name": "list_orders", "args": {"orderNo": "SO202611240002"}}])
            if step == 3:  # order_aftersale answers
                return _llm_reply("订单 SO202611240002 当前状态为已支付。")
            if step == 4:  # guide sees prior output in system prompt and calls search_products
                assert "SO202611240002" in messages[0]["content"]
                return _llm_reply("", tool_calls=[{"id": "g1", "name": "search_products", "args": {"category": "数码"}}])
            if step == 5:  # guide answers
                return _llm_reply("推荐无线蓝牙耳机 Pro（199.00 元）。")
            # step 6: synthesize
            assert "SO202611240002" in messages[1]["content"]
            assert "199.00" in messages[1]["content"]
            return _llm_reply("您的订单 SO202611240002 已支付；另为您推荐无线蓝牙耳机 Pro（199.00 元）。")

    res = await workflow.run_graph(
        session_id, "帮我查下 SO202611240002 的状态，顺便推荐个耳机", llm_client=FakeLLM()
    )

    assert res["route"] == "order_aftersale"
    assert res["agents_used"] == ["order_aftersale", "guide"]
    assert res["tool_rounds"] == 2
    assert "SO202611240002" in res["content"] and "199.00" in res["content"]
    # 验证两个专家拿到的工具集严格隔离
    assert tool_sets_seen[1] == {"list_orders", "get_order_detail", "create_aftersale", "list_aftersales"}
    assert tool_sets_seen[3] == {"list_categories", "search_products", "get_product_detail"}
    assert tool_sets_seen[5] is None  # synthesize 不带工具


async def test_consecutive_tool_failures_suggest_handoff_without_forcing(session_id, monkeypatch):
    """工具连续失败 2 次 -> 走降级兜底并建议转人工，但 handoff_requested 保持 False。"""
    all_mock = _mock_schemas()

    async def fake_all_schemas(*, allowed_names=None, include_local=True, include_mcp=True):
        return [s for s in all_mock if s["function"]["name"] in (allowed_names or set())]

    async def fake_execute(name, args, context):
        return "外部业务工具 list_orders 调用失败（TimeoutError），请稍后重试或转人工客服协助处理。"

    monkeypatch.setattr(workers, "all_schemas", fake_all_schemas)
    monkeypatch.setattr(workers, "execute", fake_execute)

    step = 0

    class FakeLLM:
        model = "deepseek-chat"

        async def chat(self, messages, tools=None):
            nonlocal step
            step += 1
            if step == 1:
                return _llm_reply('{"route":"order_aftersale","plan":["order_aftersale"],"reason":"查单"}')
            return _llm_reply("", tool_calls=[{"id": f"c{step}", "name": "list_orders", "args": {}}])

    res = await workflow.run_graph(session_id, "查一下我的订单", llm_client=FakeLLM())

    assert res["handoff_requested"] is False
    assert "转人工" in res["content"]
    assert "波动" in res["content"]


async def test_explicit_handoff_sets_handoff_requested_true(session_id):
    class FakeLLM:
        model = "deepseek-chat"

        async def chat(self, messages, tools=None):
            return _llm_reply('{"route":"handoff","plan":[],"reason":"要求人工"}')

    res = await workflow.run_graph(session_id, "给我转人工客服", llm_client=FakeLLM())

    assert res["route"] == "handoff"
    assert res["handoff_requested"] is True
    assert "人工客服" in res["content"]


async def test_run_graph_stream_emits_route_agent_tool_delta_final_and_persists(session_id, monkeypatch):
    """流式多 Agent 状态机：验证事件顺序 route -> agent_status -> tool_status -> tool_result -> delta -> final 且落库。"""
    all_mock = _mock_schemas()

    async def fake_all_schemas(*, allowed_names=None, include_local=True, include_mcp=True):
        return [s for s in all_mock if s["function"]["name"] in (allowed_names or set())]

    async def fake_categories():
        return ["数码"]

    async def fake_execute(name, args, context):
        return '{"success":true,"code":200,"data":{"records":[{"name":"无线蓝牙耳机 Pro","priceYuan":"199.00"}],"total":1}}'

    monkeypatch.setattr(workers, "all_schemas", fake_all_schemas)
    monkeypatch.setattr(workers, "fetch_on_sale_categories", fake_categories)
    monkeypatch.setattr(workers, "execute", fake_execute)

    stream_round = 0

    class FakeStreamLLM:
        model = "deepseek-chat"

        async def chat(self, messages, tools=None):
            return _llm_reply('{"route":"guide","plan":["guide"],"reason":"导购选品"}', tokens=15)

        async def chat_stream(self, messages, tools=None):
            nonlocal stream_round
            stream_round += 1
            if stream_round == 1:
                yield {
                    "type": "tool_calls",
                    "tool_calls": [{"id": "s1", "name": "search_products", "args": {"category": "数码"}}],
                }
                yield {"type": "done", "usage": {"prompt_tokens": 20, "completion_tokens": 5, "total_tokens": 25}}
                return
            yield {"type": "delta", "content": "推荐无线"}
            yield {"type": "delta", "content": "蓝牙耳机 Pro"}
            yield {"type": "done", "usage": {"prompt_tokens": 30, "completion_tokens": 8, "total_tokens": 38}}

    events = [
        ev async for ev in workflow.run_graph_stream(session_id, "推荐数码", llm_client=FakeStreamLLM())
    ]
    types = [e["type"] for e in events]

    assert types[0] == "route"
    assert events[0]["route"] == "guide"
    assert "agent_status" in types
    assert "tool_status" in types
    assert "tool_result" in types
    assert types.count("delta") == 2
    assert types[-1] == "final"

    final = events[-1]
    assert final["content"] == "推荐无线蓝牙耳机 Pro"
    assert final["tool_rounds"] == 1
    assert final["agents_used"] == ["guide"]
    assert final["usage"]["total_tokens"] == 83  # 20 + 25 + 38

    from app.chat import get_history
    history = await get_history(session_id)
    assert [m["role"] for m in history] == ["user", "assistant"]
    assert history[1]["content"] == "推荐无线蓝牙耳机 Pro"

