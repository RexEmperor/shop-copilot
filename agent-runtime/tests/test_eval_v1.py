"""Phase 4 S5 评测集 v1（100 题）契约与打分/裁判解析单元测试（无外部依赖）。"""
from app.graph.state import ROLE_TOOL_ALLOWLIST, VALID_ROUTES
from evals.run_eval_v1 import has_inline_citation, load_eval_v1, parse_judge_output, score_case_v1

ALL_KNOWN_TOOLS = set().union(*ROLE_TOOL_ALLOWLIST.values())


def test_eval_set_v1_has_100_valid_unique_cases():
    cases = load_eval_v1()
    assert len(cases) == 100
    assert len({c["id"] for c in cases}) == 100

    counts: dict[str, int] = {}
    for c in cases:
        counts[c["category"]] = counts.get(c["category"], 0) + 1
        assert c["query"].strip()
        assert set(c["expected_routes"]).issubset(VALID_ROUTES)
        for group in c.get("expected_tool_groups") or []:
            assert group and set(group).issubset(ALL_KNOWN_TOOLS)

    assert counts == {
        "guide": 8,
        "order_aftersale": 8,
        "product_qa": 2,
        "policy_rag": 42,
        "cross_domain": 4,
        "chitchat": 3,
        "handoff": 3,
        "semantic_guide": 15,
        "anti_hallucination": 15,
    }


def test_citation_and_judge_parsers():
    assert has_inline_citation("收费 69 元 [来源: 店铺延长保修服务说明]") is True
    assert has_inline_citation("收费 69 元 [来源：店铺延长保修服务说明]") is True
    assert has_inline_citation("收费 69 元") is False

    case = {
        "id": "Q03",
        "category": "policy_rag",
        "expected_routes": ["product_qa"],
        "expected_agents_all": ["product_qa"],
        "expected_tool_groups": [["search_knowledge_base"]],
        "expect_citation": True,
        "must_contain_all": ["69"],
    }
    ok = score_case_v1(
        case,
        {
            "route": "product_qa",
            "agents_used": ["product_qa"],
            "tools_called": ["search_knowledge_base"],
            "citations": [{"title": "店铺延长保修服务说明"}],
            "content": "1年延保69元 [来源: 店铺延长保修服务说明]",
        },
    )
    assert ok["passed"] is True and ok["citation_ok"] is True

    no_cit = score_case_v1(
        case,
        {
            "route": "product_qa",
            "agents_used": ["product_qa"],
            "tools_called": ["search_knowledge_base"],
            "citations": [],
            "content": "1年延保69元",
        },
    )
    assert no_cit["passed"] is False and no_cit["citation_ok"] is False

    parsed = parse_judge_output('```json\n{"faithful": true, "hallucinated": false, "reason": "准确"}\n```')
    assert parsed == {"faithful": True, "hallucinated": False, "reason": "准确"}
