"""评测集 v0 结构契约与打分逻辑确定性单元测试（不依赖外部 LLM/MCP）。"""
from app.graph.state import ROLE_TOOL_ALLOWLIST, VALID_ROUTES
from evals.run_eval_v0 import aggregate_results, load_eval_set, render_markdown_report, score_case

ALL_KNOWN_MCP_TOOLS = set().union(*ROLE_TOOL_ALLOWLIST.values())


def test_eval_set_v0_contract():
    cases = load_eval_set()
    assert len(cases) == 30

    ids = [c["id"] for c in cases]
    assert len(set(ids)) == 30

    categories = {}
    for c in cases:
        categories[c["category"]] = categories.get(c["category"], 0) + 1
        assert c["query"].strip()
        assert c["expected_routes"]
        assert set(c["expected_routes"]).issubset(VALID_ROUTES)
        for agent in c.get("expected_agents_all") or []:
            assert agent in VALID_ROUTES
        for group in c.get("expected_tool_groups") or []:
            assert group and set(group).issubset(ALL_KNOWN_MCP_TOOLS)
        for tool in c.get("forbidden_tools") or []:
            assert tool in ALL_KNOWN_MCP_TOOLS

    assert categories == {
        "guide": 8,
        "order_aftersale": 8,
        "product_qa": 4,
        "cross_domain": 4,
        "chitchat": 3,
        "handoff": 3,
    }


def test_score_case_pass_and_failure_dimensions():
    case = {
        "id": "X01",
        "category": "cross_domain",
        "description": "跨域协作",
        "expected_routes": ["order_aftersale", "guide"],
        "expected_agents_all": ["order_aftersale", "guide"],
        "expected_tool_groups": [
            ["get_order_detail", "list_orders"],
            ["search_products", "get_product_detail"],
        ],
        "forbidden_tools": ["create_aftersale"],
        "must_contain_all": ["SO202611240002", "199"],
    }

    ok = score_case(
        case,
        {
            "route": "order_aftersale",
            "agents_used": ["order_aftersale", "guide"],
            "tools_called": ["get_order_detail", "search_products"],
            "handoff_requested": False,
            "content": "订单 SO202611240002 已支付；推荐无线蓝牙耳机 Pro，199.00 元。",
        },
    )
    assert ok["passed"] is True
    assert ok["reasons"] == []

    bad = score_case(
        case,
        {
            "route": "guide",
            "agents_used": ["guide"],
            "tools_called": ["search_products", "create_aftersale"],
            "handoff_requested": False,
            "content": "推荐耳机 199 元。",
        },
    )
    assert bad["route_ok"] is False
    assert bad["tool_ok"] is False
    assert bad["fact_ok"] is False
    assert bad["passed"] is False


def test_score_case_zero_tools_and_handoff():
    case = {
        "id": "H01",
        "category": "handoff",
        "description": "转人工",
        "expected_routes": ["handoff"],
        "expected_agents_all": ["handoff"],
        "expect_zero_tools": True,
        "expected_handoff": True,
        "must_contain_any": ["人工客服"],
    }
    res_ok = score_case(
        case,
        {
            "route": "handoff",
            "agents_used": ["handoff"],
            "tools_called": [],
            "handoff_requested": True,
            "content": "已为您记录转人工客服请求。",
        },
    )
    assert res_ok["passed"] is True

    res_bad = score_case(
        case,
        {
            "route": "handoff",
            "agents_used": ["handoff"],
            "tools_called": ["list_orders"],
            "handoff_requested": False,
            "content": "已为您记录转人工客服请求。",
        },
    )
    assert res_bad["route_ok"] is False
    assert res_bad["tool_ok"] is False
    assert res_bad["passed"] is False


def test_aggregate_and_markdown_render():
    records = [
        {
            "id": "C01",
            "category": "chitchat",
            "description": "打招呼",
            "elapsed_s": 1.0,
            "usage": {"total_tokens": 500},
            "actual": {"route": "chitchat", "agents_used": ["chitchat"], "tools_called": []},
            "score": {"route_ok": True, "tool_ok": True, "fact_ok": True, "passed": True, "reasons": []},
        },
        {
            "id": "G01",
            "category": "guide",
            "description": "查类目",
            "elapsed_s": 3.0,
            "usage": {"total_tokens": 1500},
            "actual": {"route": "guide", "agents_used": ["guide"], "tools_called": ["list_categories"]},
            "score": {"route_ok": True, "tool_ok": True, "fact_ok": False, "passed": False, "reasons": ["missing keyword '家居'"]},
        },
    ]
    summary = aggregate_results(records)
    assert summary["overall"]["total"] == 2
    assert summary["overall"]["passed"] == 1
    assert summary["overall"]["pass_rate"] == 50.0
    assert summary["overall"]["total_tokens"] == 2000

    md = render_markdown_report(
        {
            "run_id": "abc123",
            "generated_at": "2026-11-26T12:00:00Z",
            "concurrency": 3,
            "wall_time_s": 4.0,
            "summary": summary,
            "results": records,
        }
    )
    assert "Phase 3 评测报告" in md
    assert "G01" in md
    assert "missing keyword '家居'" in md
