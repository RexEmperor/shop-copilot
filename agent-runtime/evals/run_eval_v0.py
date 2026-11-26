"""Phase 3 评测集 v0 自动化评测运行器。

用法：
    cd agent-runtime && uv run python -m evals.run_eval_v0 --concurrency 3
    cd agent-runtime && uv run python -m evals.run_eval_v0 --limit 5
"""
import argparse
import asyncio
import json
import re
import time
import uuid
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from langfuse import get_client

from app.graph import run_graph_stream
from app.mcp_client import mcp_client

EVAL_DIR = Path(__file__).resolve().parent
DEFAULT_DATASET = EVAL_DIR / "eval_set_v0.json"
DEFAULT_JSON_REPORT = EVAL_DIR / "report_v0.json"
DEFAULT_MD_REPORT = EVAL_DIR / "report_v0.md"


def load_eval_set(path: Path = DEFAULT_DATASET) -> list[dict[str, Any]]:
    return json.loads(path.read_text(encoding="utf-8"))


def _contains_keyword(content: str, keyword: str) -> bool:
    """支持直接子串匹配与忽略空白字符匹配（避免中英文排版空格导致误判，如 '87 键' vs '87键'）。"""
    if keyword in content:
        return True
    compact_kw = re.sub(r"\s+", "", keyword)
    if not compact_kw:
        return True
    return compact_kw in re.sub(r"\s+", "", content)


def score_case(case: dict[str, Any], actual: dict[str, Any]) -> dict[str, Any]:
    """对单条评测结果进行三维度打分：路由/协作、工具调用、事实/内容契约。"""
    route = actual.get("route") or ""
    agents_used = list(actual.get("agents_used") or [])
    tools_called = list(actual.get("tools_called") or [])
    handoff_requested = bool(actual.get("handoff_requested", False))
    content = actual.get("content") or ""

    # 1. 路由 & 专家协作判定
    route_reasons: list[str] = []
    expected_routes = case.get("expected_routes") or []
    if expected_routes and route not in expected_routes:
        route_reasons.append(f"route={route!r} not in {expected_routes}")

    expected_agents_all = case.get("expected_agents_all") or []
    missing_agents = [a for a in expected_agents_all if a not in agents_used]
    if missing_agents:
        route_reasons.append(f"missing agents {missing_agents} (actual={agents_used})")

    if "expected_handoff" in case:
        if handoff_requested != bool(case["expected_handoff"]):
            route_reasons.append(
                f"handoff_requested={handoff_requested} != {case['expected_handoff']}"
            )
    route_ok = len(route_reasons) == 0

    # 2. 工具调用判定
    tool_reasons: list[str] = []
    called_set = set(tools_called)
    if case.get("expect_zero_tools"):
        if tools_called:
            tool_reasons.append(f"expected 0 tools, got {tools_called}")
    else:
        for idx, group in enumerate(case.get("expected_tool_groups") or []):
            if not (called_set & set(group)):
                tool_reasons.append(f"group[{idx}] none of {group} called (actual={tools_called})")

    forbidden = set(case.get("forbidden_tools") or [])
    hit_forbidden = sorted(called_set & forbidden)
    if hit_forbidden:
        tool_reasons.append(f"called forbidden tools {hit_forbidden}")
    tool_ok = len(tool_reasons) == 0

    # 3. 事实/内容契约判定
    fact_reasons: list[str] = []
    if not content.strip():
        fact_reasons.append("empty reply")
    else:
        for kw in case.get("must_contain_all") or []:
            if not _contains_keyword(content, kw):
                fact_reasons.append(f"missing keyword {kw!r}")
        any_kws = case.get("must_contain_any") or []
        if any_kws and not any(_contains_keyword(content, kw) for kw in any_kws):
            fact_reasons.append(f"none of {any_kws} found in reply")
        for kw in case.get("must_not_contain") or []:
            if _contains_keyword(content, kw):
                fact_reasons.append(f"forbidden keyword {kw!r} found in reply")
    fact_ok = len(fact_reasons) == 0

    return {
        "route_ok": route_ok,
        "tool_ok": tool_ok,
        "fact_ok": fact_ok,
        "passed": route_ok and tool_ok and fact_ok,
        "reasons": route_reasons + tool_reasons + fact_reasons,
    }


def aggregate_results(records: list[dict[str, Any]]) -> dict[str, Any]:
    """按类别和全局汇总评测指标。"""
    def _summarize(items: list[dict[str, Any]]) -> dict[str, Any]:
        total = len(items)
        if total == 0:
            return {
                "total": 0,
                "passed": 0,
                "route_acc": 0.0,
                "tool_acc": 0.0,
                "fact_acc": 0.0,
                "pass_rate": 0.0,
                "avg_elapsed_s": 0.0,
                "total_tokens": 0,
            }
        route_ok = sum(1 for r in items if r["score"]["route_ok"])
        tool_ok = sum(1 for r in items if r["score"]["tool_ok"])
        fact_ok = sum(1 for r in items if r["score"]["fact_ok"])
        passed = sum(1 for r in items if r["score"]["passed"])
        avg_elapsed = round(sum(float(r.get("elapsed_s", 0.0)) for r in items) / total, 2)
        total_tokens = sum(int((r.get("usage") or {}).get("total_tokens", 0)) for r in items)
        return {
            "total": total,
            "passed": passed,
            "route_ok": route_ok,
            "tool_ok": tool_ok,
            "fact_ok": fact_ok,
            "route_acc": round(route_ok / total * 100, 1),
            "tool_acc": round(tool_ok / total * 100, 1),
            "fact_acc": round(fact_ok / total * 100, 1),
            "pass_rate": round(passed / total * 100, 1),
            "avg_elapsed_s": avg_elapsed,
            "total_tokens": total_tokens,
        }

    by_cat: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for rec in records:
        by_cat[rec["category"]].append(rec)

    return {
        "overall": _summarize(records),
        "by_category": {cat: _summarize(items) for cat, items in by_cat.items()},
    }


def render_markdown_report(report: dict[str, Any]) -> str:
    overall = report["summary"]["overall"]
    lines = [
        "# Phase 3 评测报告（评测集 v0 · 30 题）",
        "",
        f"- 运行时间（UTC）：`{report['generated_at']}`",
        f"- 运行批次：`{report['run_id']}`",
        f"- 总耗时：`{report['wall_time_s']}s`（并发度 `{report['concurrency']}`）",
        "",
        "## 1. 汇总指标",
        "",
        "| 维度 / 类别 | 题数 | 路由/协作准确率 | 工具调用准确率 | 事实/内容通过率 | 综合通过率 | 平均耗时(s) | Token 消耗 |",
        "|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for cat, stats in report["summary"]["by_category"].items():
        lines.append(
            f"| `{cat}` | {stats['total']} | "
            f"{stats['route_ok']}/{stats['total']} ({stats['route_acc']}%) | "
            f"{stats['tool_ok']}/{stats['total']} ({stats['tool_acc']}%) | "
            f"{stats['fact_ok']}/{stats['total']} ({stats['fact_acc']}%) | "
            f"{stats['passed']}/{stats['total']} ({stats['pass_rate']}%) | "
            f"{stats['avg_elapsed_s']} | {stats['total_tokens']} |"
        )
    lines.append(
        f"| **总计** | **{overall['total']}** | "
        f"**{overall['route_ok']}/{overall['total']} ({overall['route_acc']}%)** | "
        f"**{overall['tool_ok']}/{overall['total']} ({overall['tool_acc']}%)** | "
        f"**{overall['fact_ok']}/{overall['total']} ({overall['fact_acc']}%)** | "
        f"**{overall['passed']}/{overall['total']} ({overall['pass_rate']}%)** | "
        f"**{overall['avg_elapsed_s']}** | **{overall['total_tokens']}** |"
    )

    failed = [r for r in report["results"] if not r["score"]["passed"]]
    lines.extend(["", "## 2. 失败/偏差样本明细", ""])
    if not failed:
        lines.append("全部样本通过，无失败项。")
    else:
        for r in failed:
            lines.append(
                f"- **{r['id']}**（`{r['category']}` · {r['description']}）："
                f"route=`{r['actual']['route']}` agents=`{r['actual']['agents_used']}` "
                f"tools=`{r['actual']['tools_called']}`；原因：`{'; '.join(r['score']['reasons'])}`"
            )

    lines.extend([
        "",
        "## 3. 全量逐题结果",
        "",
        "| ID | 类别 | 描述 | 路由 | 专家 | 调用工具 | 耗时(s) | 结果 |",
        "|---|---|---|---|---|---|---:|---|",
    ])
    for r in report["results"]:
        status = "PASS" if r["score"]["passed"] else "FAIL"
        tools_str = ", ".join(r["actual"]["tools_called"]) or "-"
        agents_str = " → ".join(r["actual"]["agents_used"]) or "-"
        lines.append(
            f"| {r['id']} | `{r['category']}` | {r['description']} | "
            f"`{r['actual']['route']}` | `{agents_str}` | `{tools_str}` | "
            f"{r['elapsed_s']} | **{status}** |"
        )
    lines.append("")
    return "\n".join(lines)


async def run_single_case(
    case: dict[str, Any],
    run_id: str,
    semaphore: asyncio.Semaphore,
) -> dict[str, Any]:
    session_id = f"eval-v0-{run_id}-{case['id']}"
    async with semaphore:
        t0 = time.perf_counter()
        tools_called: list[str] = []
        final_event: dict[str, Any] = {}
        error_msg: str | None = None
        try:
            async for ev in run_graph_stream(session_id, case["query"]):
                ev_type = ev.get("type")
                if ev_type == "tool_status" and ev.get("name"):
                    tools_called.append(ev["name"])
                elif ev_type == "final":
                    final_event = ev
        except Exception as exc:
            error_msg = repr(exc)

        elapsed_s = round(time.perf_counter() - t0, 2)
        actual = {
            "route": final_event.get("route", ""),
            "plan": final_event.get("plan") or [],
            "agents_used": final_event.get("agents_used") or [],
            "tools_called": tools_called,
            "tool_rounds": int(final_event.get("tool_rounds", 0)),
            "handoff_requested": bool(final_event.get("handoff_requested", False)),
            "content": final_event.get("content", ""),
            "error": error_msg,
        }
        score = score_case(case, actual)
        if error_msg:
            score["passed"] = False
            score["reasons"].append(f"exception: {error_msg}")

        tag = "PASS" if score["passed"] else "FAIL"
        print(
            f"[{tag}] {case['id']} ({case['category']}) "
            f"route={actual['route']} agents={actual['agents_used']} "
            f"tools={tools_called} {elapsed_s}s",
            flush=True,
        )
        if not score["passed"]:
            print(f"       reasons: {score['reasons']}", flush=True)

        return {
            "id": case["id"],
            "category": case["category"],
            "description": case["description"],
            "query": case["query"],
            "session_id": session_id,
            "elapsed_s": elapsed_s,
            "usage": final_event.get("usage") or {},
            "actual": actual,
            "score": score,
        }


async def run_evaluation(
    dataset_path: Path = DEFAULT_DATASET,
    json_out: Path = DEFAULT_JSON_REPORT,
    md_out: Path = DEFAULT_MD_REPORT,
    limit: int | None = None,
    concurrency: int = 3,
) -> dict[str, Any]:
    cases = load_eval_set(dataset_path)
    if limit is not None:
        cases = cases[:limit]

    # 预热 MCP 工具缓存，避免并发首批同时触发 discover
    tools = await mcp_client.list_tools(force_refresh=True)
    tool_names = sorted(tools.keys()) if isinstance(tools, dict) else [getattr(t, "name", str(t)) for t in tools]
    print(f"Loaded {len(cases)} eval cases; MCP tools discovered: {tool_names}", flush=True)

    run_id = uuid.uuid4().hex[:6]
    sem = asyncio.Semaphore(max(1, concurrency))
    wall_start = time.perf_counter()

    tasks = [run_single_case(case, run_id, sem) for case in cases]
    results = await asyncio.gather(*tasks)
    wall_time_s = round(time.perf_counter() - wall_start, 2)

    summary = aggregate_results(results)
    report = {
        "run_id": run_id,
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "dataset": str(dataset_path.name),
        "concurrency": concurrency,
        "wall_time_s": wall_time_s,
        "summary": summary,
        "results": results,
    }

    json_out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    md_text = render_markdown_report(report)
    md_out.write_text(md_text, encoding="utf-8")

    try:
        get_client().flush()
    except Exception:
        pass

    overall = summary["overall"]
    print("\n=== EVAL v0 SUMMARY ===", flush=True)
    print(
        f"Total={overall['total']} | Route={overall['route_ok']}/{overall['total']} ({overall['route_acc']}%) | "
        f"Tools={overall['tool_ok']}/{overall['total']} ({overall['tool_acc']}%) | "
        f"Facts={overall['fact_ok']}/{overall['total']} ({overall['fact_acc']}%) | "
        f"Overall={overall['passed']}/{overall['total']} ({overall['pass_rate']}%) | "
        f"Avg={overall['avg_elapsed_s']}s | Tokens={overall['total_tokens']} | Wall={wall_time_s}s",
        flush=True,
    )
    print(f"Saved JSON report -> {json_out}", flush=True)
    print(f"Saved MD report   -> {md_out}", flush=True)
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description="Run Phase 3 eval set v0 against live LangGraph + MCP.")
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--json-out", type=Path, default=DEFAULT_JSON_REPORT)
    parser.add_argument("--md-out", type=Path, default=DEFAULT_MD_REPORT)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--concurrency", type=int, default=3)
    args = parser.parse_args()

    asyncio.run(
        run_evaluation(
            dataset_path=args.dataset,
            json_out=args.json_out,
            md_out=args.md_out,
            limit=args.limit,
            concurrency=args.concurrency,
        )
    )


if __name__ == "__main__":
    main()
