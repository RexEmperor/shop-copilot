"""Phase 4 评测集 v1（100 题）自动化评测运行器：
1. 57 题 RAG 检索消融对比（Vector-only vs BM25-only vs Hybrid RRF）+ 15 题库外检索硬拦截率；
2. 全量 100 题端到端多 Agent 评测（路由/协作、工具调用、事实/内容、引用溯源率）；
3. LLM-as-Judge 忠实度与幻觉率自动评判（针对全部 57 题政策/库外防幻觉样本）。

用法：
    cd agent-runtime && uv run python -m evals.run_eval_v1 --concurrency 5
    cd agent-runtime && uv run python -m evals.run_eval_v1 --retrieval-only
"""
import argparse
import asyncio
from collections import defaultdict
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import time
from typing import Any
import uuid

from langfuse import get_client

from app import config
from app.graph import run_graph_stream
from app.llm.client import DeepSeekClient
from app.mcp_client import mcp_client
from app.rag.retriever import HybridRetriever
from evals.run_eval_v0 import score_case

EVAL_DIR = Path(__file__).resolve().parent
DEFAULT_DATASET = EVAL_DIR / "eval_set_v1.json"
DEFAULT_JSON_REPORT = EVAL_DIR / "report_v1.json"
DEFAULT_MD_REPORT = EVAL_DIR / "report_v1.md"

_CITATION_RE = re.compile(r"\[来源[:：]\s*[^\]]+\]")

_JUDGE_SYSTEM_PROMPT = (
    "你是严谨的电商 AI 评测裁判（LLM-as-Judge）。请仅输出合法 JSON，不要输出任何额外文字或代码块。\n"
    "判断规则：\n"
    "1. 若该题为知识库内规则题（is_out_of_kb=false）：核对助手回答中的数字、比例、时限和结论是否与检索证据一致，"
    "未编造证据之外的虚假数字或承诺，则 faithful=true, hallucinated=false。\n"
    "2. 若该题为库外防幻觉题（is_out_of_kb=true）：只要助手明确表示未查到/无法确认且未编造具体税率、折价、门店地址、手续费等虚假规则，"
    "即使建议转人工，也判为 faithful=true, hallucinated=false；若编造了具体政策数字或承诺则 faithful=false, hallucinated=true。\n"
    '输出格式：{"faithful": true, "hallucinated": false, "reason": "不超过25字说明"}'
)


def load_eval_v1(path: Path = DEFAULT_DATASET) -> list[dict[str, Any]]:
    return json.loads(path.read_text(encoding="utf-8"))


def parse_judge_output(raw: str) -> dict[str, Any]:
    text = (raw or "").strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text, flags=re.IGNORECASE).strip()
    try:
        data = json.loads(text)
        if isinstance(data, dict) and "faithful" in data and "hallucinated" in data:
            return {
                "faithful": bool(data["faithful"]),
                "hallucinated": bool(data["hallucinated"]),
                "reason": str(data.get("reason") or ""),
            }
    except json.JSONDecodeError:
        pass
    m = re.search(r"\{.*\}", text, flags=re.DOTALL)
    if m:
        try:
            data = json.loads(m.group(0))
            return {
                "faithful": bool(data.get("faithful", False)),
                "hallucinated": bool(data.get("hallucinated", True)),
                "reason": str(data.get("reason") or ""),
            }
        except json.JSONDecodeError:
            pass
    return {"faithful": False, "hallucinated": True, "reason": f"judge_parse_error: {text[:60]}"}


def has_inline_citation(text: str) -> bool:
    return bool(_CITATION_RE.search(text or ""))


def score_case_v1(case: dict[str, Any], actual: dict[str, Any]) -> dict[str, Any]:
    """扩展 v0 打分器：叠加引用溯源检查与 RAG 命中检查。"""
    base = score_case(case, actual)
    citation_ok = True
    if case.get("expect_citation"):
        content = actual.get("content") or ""
        citations = actual.get("citations") or []
        if not has_inline_citation(content) and not citations:
            citation_ok = False
            base["reasons"].append("missing citation [来源: ...]")
        elif not has_inline_citation(content):
            citation_ok = False
            base["reasons"].append("citations returned by tool but missing inline [来源: ...] tag")

    base["citation_ok"] = citation_ok
    base["passed"] = base["passed"] and citation_ok
    return base


async def run_retrieval_ablation(cases: list[dict[str, Any]]) -> dict[str, Any]:
    """对全部带 expected_doc_keys 的 57 题运行 Vector / BM25 / Hybrid 消融实验，以及 15 题库外拦截率测试。"""
    retriever = HybridRetriever()
    rag_cases = [c for c in cases if c.get("expected_doc_keys")]
    ood_cases = [c for c in cases if c.get("is_out_of_kb")]

    modes_summary: dict[str, Any] = {}
    for mode in ("vector", "bm25", "hybrid"):
        top1 = top3 = 0
        mrr3 = 0.0
        by_sub: dict[str, dict[str, int]] = defaultdict(lambda: {"total": 0, "top1": 0, "top3": 0})
        t0 = time.perf_counter()
        misses = []
        for c in rag_cases:
            st = "product" if c["category"] == "semantic_guide" else "policy"
            min_sc = config.RAG_MIN_VECTOR_SCORE if st == "product" else config.RAG_POLICY_MIN_VECTOR_SCORE
            hits = await retriever.retrieve(
                c["query"],
                top_k=3,
                source_type=st,
                mode=mode,
                min_vector_score=min_sc,
                strict_vector_threshold=(st == "policy" and mode == "hybrid"),
            )
            got = [h.doc_key for h in hits]
            exp = set(c["expected_doc_keys"])
            sub = by_sub[st]
            sub["total"] += 1
            if got and got[0] in exp:
                top1 += 1
                sub["top1"] += 1
            rank = next((i + 1 for i, dk in enumerate(got) if dk in exp), None)
            if rank is not None:
                top3 += 1
                sub["top3"] += 1
                mrr3 += 1.0 / rank
            else:
                misses.append({"id": c["id"], "got": got, "expected": list(exp)})

        elapsed_s = time.perf_counter() - t0
        n = len(rag_cases)
        modes_summary[mode] = {
            "total": n,
            "top1": top1,
            "top1_rate": round(top1 / n * 100, 1) if n else 0.0,
            "top3": top3,
            "top3_rate": round(top3 / n * 100, 1) if n else 0.0,
            "mrr3": round(mrr3 / n, 3) if n else 0.0,
            "avg_ms": round(elapsed_s / n * 1000, 1) if n else 0.0,
            "by_source_type": {
                k: {
                    **v,
                    "top3_rate": round(v["top3"] / v["total"] * 100, 1) if v["total"] else 0.0,
                }
                for k, v in by_sub.items()
            },
            "misses": misses,
        }

    # 15 题库外检索硬拦截统计
    blocked = 0
    for c in ood_cases:
        hits = await retriever.retrieve(
            c["query"],
            top_k=3,
            source_type="policy",
            mode="hybrid",
            min_vector_score=config.RAG_POLICY_MIN_VECTOR_SCORE,
            strict_vector_threshold=True,
        )
        if len(hits) == 0:
            blocked += 1

    return {
        "modes": modes_summary,
        "ood_guardrail": {
            "total": len(ood_cases),
            "hard_blocked": blocked,
            "hard_block_rate": round(blocked / len(ood_cases) * 100, 1) if ood_cases else 0.0,
        },
    }


async def judge_faithfulness(
    judge_llm: DeepSeekClient,
    case: dict[str, Any],
    reply: str,
    tool_outputs: list[str],
) -> dict[str, Any]:
    evidence = "\n\n".join(tool_outputs)[:3000] if tool_outputs else "（无检索命中 / 触发库外护栏）"
    user_prompt = (
        f"is_out_of_kb: {bool(case.get('is_out_of_kb', False))}\n"
        f"用户问题：{case['query']}\n\n"
        f"工具检索到的原文证据：\n{evidence}\n\n"
        f"助手最终回复：\n{reply}"
    )
    res = await judge_llm.chat(
        [
            {"role": "system", "content": _JUDGE_SYSTEM_PROMPT},
            {"role": "user", "content": user_prompt},
        ],
        tools=None,
    )
    return parse_judge_output(res.get("content") or "")


async def run_single_e2e(
    case: dict[str, Any],
    run_id: str,
    sem: asyncio.Semaphore,
    judge_llm: DeepSeekClient | None,
) -> dict[str, Any]:
    session_id = f"eval-v1-{run_id}-{case['id']}"
    async with sem:
        t0 = time.perf_counter()
        tools_called: list[str] = []
        tool_outputs: list[str] = []
        citations: list[dict] = []
        final_event: dict[str, Any] = {}
        error_msg: str | None = None
        try:
            async for ev in run_graph_stream(session_id, case["query"]):
                ev_type = ev.get("type")
                if ev_type == "tool_status" and ev.get("name"):
                    tools_called.append(ev["name"])
                elif ev_type == "tool_result" and ev.get("output"):
                    tool_outputs.append(str(ev["output"]))
                elif ev_type == "citations":
                    citations.extend(ev.get("citations") or [])
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
            "citations": final_event.get("citations") or citations,
            "tool_rounds": int(final_event.get("tool_rounds", 0)),
            "handoff_requested": bool(final_event.get("handoff_requested", False)),
            "content": final_event.get("content", ""),
            "error": error_msg,
        }
        score = score_case_v1(case, actual)
        if error_msg:
            score["passed"] = False
            score["reasons"].append(f"exception: {error_msg}")

        judge_res = None
        if judge_llm is not None and case["category"] in {"policy_rag", "anti_hallucination"}:
            try:
                judge_res = await judge_faithfulness(judge_llm, case, actual["content"], tool_outputs)
            except Exception as exc:
                judge_res = {"faithful": False, "hallucinated": True, "reason": f"judge_err:{exc!r}"}

        tag = "PASS" if score["passed"] else "FAIL"
        j_str = ""
        if judge_res:
            j_str = f" judge={'OK' if judge_res['faithful'] and not judge_res['hallucinated'] else 'HALLUC'}"
        print(
            f"[{tag}] {case['id']} ({case['category']}) route={actual['route']} "
            f"tools={tools_called} {elapsed_s}s{j_str}",
            flush=True,
        )
        if not score["passed"]:
            print(f"       reasons: {score['reasons']}", flush=True)

        return {
            "id": case["id"],
            "category": case["category"],
            "description": case["description"],
            "query": case["query"],
            "elapsed_s": elapsed_s,
            "usage": final_event.get("usage") or {},
            "actual": actual,
            "score": score,
            "judge": judge_res,
        }


def aggregate_v1(records: list[dict[str, Any]]) -> dict[str, Any]:
    def _sum(items: list[dict[str, Any]]) -> dict[str, Any]:
        n = len(items)
        if n == 0:
            return {"total": 0}
        route_ok = sum(1 for r in items if r["score"]["route_ok"])
        tool_ok = sum(1 for r in items if r["score"]["tool_ok"])
        fact_ok = sum(1 for r in items if r["score"]["fact_ok"])
        cit_items = [r for r in items if r["category"] == "policy_rag"]
        cit_ok = sum(1 for r in cit_items if r["score"].get("citation_ok", True))
        passed = sum(1 for r in items if r["score"]["passed"])
        judged = [r for r in items if r.get("judge") is not None]
        faithful = sum(1 for r in judged if r["judge"].get("faithful"))
        halluc = sum(1 for r in judged if r["judge"].get("hallucinated"))
        return {
            "total": n,
            "passed": passed,
            "pass_rate": round(passed / n * 100, 1),
            "route_ok": route_ok,
            "route_acc": round(route_ok / n * 100, 1),
            "tool_ok": tool_ok,
            "tool_acc": round(tool_ok / n * 100, 1),
            "fact_ok": fact_ok,
            "fact_acc": round(fact_ok / n * 100, 1),
            "citation_total": len(cit_items),
            "citation_ok": cit_ok,
            "citation_rate": round(cit_ok / len(cit_items) * 100, 1) if cit_items else None,
            "judged_total": len(judged),
            "faithful_count": faithful,
            "faithfulness_rate": round(faithful / len(judged) * 100, 1) if judged else None,
            "hallucination_count": halluc,
            "hallucination_rate": round(halluc / len(judged) * 100, 1) if judged else None,
            "avg_elapsed_s": round(sum(float(r["elapsed_s"]) for r in items) / n, 2),
            "total_tokens": sum(int((r.get("usage") or {}).get("total_tokens", 0)) for r in items),
        }

    by_cat: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for r in records:
        by_cat[r["category"]].append(r)

    return {
        "overall": _sum(records),
        "by_category": {cat: _sum(items) for cat, items in by_cat.items()},
    }


def render_markdown_v1(report: dict[str, Any]) -> str:
    ab = report["retrieval_ablation"]
    lines = [
        "# Phase 4 评测报告（评测集 v1 · 100 题）",
        "",
        f"- 运行时间（UTC）：`{report['generated_at']}`",
        f"- 运行批次：`{report['run_id']}`",
        f"- 总耗时：`{report['wall_time_s']}s`（并发度 `{report['concurrency']}`）",
        "",
        "## 1. RAG 检索消融对比（57 题：42 规则 + 15 语义导购）",
        "",
        "| 检索模式 | Top-1 命中率 | Top-3 命中率 | MRR@3 | 规则 Top-3 | 语义选品 Top-3 | 单次平均耗时(ms) |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for mode in ("bm25", "vector", "hybrid"):
        m = ab["modes"][mode]
        pol = m["by_source_type"].get("policy", {})
        prd = m["by_source_type"].get("product", {})
        lines.append(
            f"| `{mode}` | {m['top1']}/{m['total']} ({m['top1_rate']}%) | "
            f"**{m['top3']}/{m['total']} ({m['top3_rate']}%)** | {m['mrr3']} | "
            f"{pol.get('top3', 0)}/{pol.get('total', 0)} ({pol.get('top3_rate', 0)}%) | "
            f"{prd.get('top3', 0)}/{prd.get('total', 0)} ({prd.get('top3_rate', 0)}%) | {m['avg_ms']} |"
        )
    ood = ab["ood_guardrail"]
    lines.extend([
        "",
        f"- 库外防幻觉检索硬拦截率（`strict_vector_threshold=0.55`）：**{ood['hard_blocked']}/{ood['total']} ({ood['hard_block_rate']}%)**（其余边缘命中由模型层软护栏兜底）",
    ])

    if report.get("e2e_summary"):
        ov = report["e2e_summary"]["overall"]
        lines.extend([
            "",
            "## 2. 全量 100 题端到端多 Agent + LLM-as-Judge 汇总",
            "",
            "| 类别 | 题数 | 路由/协作 | 工具调用 | 事实/内容 | 综合通过率 | 均耗时(s) | Token 消耗 |",
            "|---|---:|---:|---:|---:|---:|---:|---:|",
        ])
        for cat, st in report["e2e_summary"]["by_category"].items():
            lines.append(
                f"| `{cat}` | {st['total']} | {st['route_ok']}/{st['total']} ({st['route_acc']}%) | "
                f"{st['tool_ok']}/{st['total']} ({st['tool_acc']}%) | "
                f"{st['fact_ok']}/{st['total']} ({st['fact_acc']}%) | "
                f"{st['passed']}/{st['total']} ({st['pass_rate']}%) | "
                f"{st['avg_elapsed_s']} | {st['total_tokens']} |"
            )
        lines.append(
            f"| **总计** | **{ov['total']}** | **{ov['route_ok']}/{ov['total']} ({ov['route_acc']}%)** | "
            f"**{ov['tool_ok']}/{ov['total']} ({ov['tool_acc']}%)** | "
            f"**{ov['fact_ok']}/{ov['total']} ({ov['fact_acc']}%)** | "
            f"**{ov['passed']}/{ov['total']} ({ov['pass_rate']}%)** | "
            f"**{ov['avg_elapsed_s']}** | **{ov['total_tokens']}** |"
        )
        lines.extend([
            "",
            "### 关键 RAG 质量指标",
            f"- **规则问答引用溯源率**：`{ov['citation_ok']}/{ov['citation_total']}` (**{ov['citation_rate']}%**)",
            f"- **LLM-as-Judge 忠实度**（57 题规则+库外防幻觉）：`{ov['faithful_count']}/{ov['judged_total']}` (**{ov['faithfulness_rate']}%**)",
            f"- **LLM-as-Judge 幻觉率**：`{ov['hallucination_count']}/{ov['judged_total']}` (**{ov['hallucination_rate']}%**)",
        ])

        failed = [r for r in report["results"] if not r["score"]["passed"]]
        lines.extend(["", "## 3. 端到端失败/偏差样本", ""])
        if not failed:
            lines.append("全部样本通过，无失败项。")
        else:
            for r in failed:
                lines.append(
                    f"- **{r['id']}**（`{r['category']}` · {r['description']}）："
                    f"route=`{r['actual']['route']}` tools=`{r['actual']['tools_called']}`；"
                    f"原因：`{'; '.join(r['score']['reasons'])}`"
                )

        hallucs = [r for r in report["results"] if r.get("judge") and r["judge"].get("hallucinated")]
        lines.extend(["", "## 4. LLM-as-Judge 判定的幻觉样本", ""])
        if not hallucs:
            lines.append("0 例幻觉。")
        else:
            for r in hallucs:
                lines.append(
                    f"- **{r['id']}**（`{r['category']}` · {r['description']}）：`{r['judge']['reason']}`"
                )
    lines.append("")
    return "\n".join(lines)


async def run_eval_v1(
    dataset_path: Path = DEFAULT_DATASET,
    json_out: Path = DEFAULT_JSON_REPORT,
    md_out: Path = DEFAULT_MD_REPORT,
    concurrency: int = 5,
    retrieval_only: bool = False,
    no_judge: bool = False,
) -> dict[str, Any]:
    cases = load_eval_v1(dataset_path)
    run_id = uuid.uuid4().hex[:6]
    wall_start = time.perf_counter()

    print(f"Loaded {len(cases)} cases from {dataset_path.name}; running retrieval ablation...", flush=True)
    ablation = await run_retrieval_ablation(cases)
    for mode in ("bm25", "vector", "hybrid"):
        m = ablation["modes"][mode]
        print(
            f"[RETRIEVAL {mode:6s}] Top1={m['top1']}/{m['total']} ({m['top1_rate']}%) | "
            f"Top3={m['top3']}/{m['total']} ({m['top3_rate']}%) | MRR@3={m['mrr3']} | Avg={m['avg_ms']}ms",
            flush=True,
        )
    ood = ablation["ood_guardrail"]
    print(f"[RETRIEVAL OOD   ] hard_blocked={ood['hard_blocked']}/{ood['total']} ({ood['hard_block_rate']}%)", flush=True)

    results: list[dict[str, Any]] = []
    e2e_summary = None
    if not retrieval_only:
        await mcp_client.list_tools(force_refresh=True)
        judge_llm = None if no_judge else DeepSeekClient()
        sem = asyncio.Semaphore(max(1, concurrency))
        results = await asyncio.gather(
            *(run_single_e2e(c, run_id, sem, judge_llm) for c in cases)
        )
        e2e_summary = aggregate_v1(results)

    wall_time_s = round(time.perf_counter() - wall_start, 2)
    report = {
        "run_id": run_id,
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "dataset": dataset_path.name,
        "concurrency": concurrency,
        "wall_time_s": wall_time_s,
        "retrieval_ablation": ablation,
        "e2e_summary": e2e_summary,
        "results": results,
    }
    json_out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    md_out.write_text(render_markdown_v1(report), encoding="utf-8")

    try:
        get_client().flush()
    except Exception:
        pass

    if e2e_summary:
        ov = e2e_summary["overall"]
        print("\n=== EVAL v1 (100 QUERIES) SUMMARY ===", flush=True)
        print(
            f"Total={ov['total']} | Route={ov['route_ok']}/{ov['total']} ({ov['route_acc']}%) | "
            f"Tools={ov['tool_ok']}/{ov['total']} ({ov['tool_acc']}%) | "
            f"Facts={ov['fact_ok']}/{ov['total']} ({ov['fact_acc']}%) | "
            f"Citations={ov['citation_ok']}/{ov['citation_total']} ({ov['citation_rate']}%) | "
            f"Faithful={ov['faithful_count']}/{ov['judged_total']} ({ov['faithfulness_rate']}%) | "
            f"Halluc={ov['hallucination_count']}/{ov['judged_total']} ({ov['hallucination_rate']}%) | "
            f"Overall={ov['passed']}/{ov['total']} ({ov['pass_rate']}%) | "
            f"Avg={ov['avg_elapsed_s']}s | Tokens={ov['total_tokens']} | Wall={wall_time_s}s",
            flush=True,
        )
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description="Run Phase 4 100-query eval v1.")
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--json-out", type=Path, default=DEFAULT_JSON_REPORT)
    parser.add_argument("--md-out", type=Path, default=DEFAULT_MD_REPORT)
    parser.add_argument("--concurrency", type=int, default=5)
    parser.add_argument("--retrieval-only", action="store_true")
    parser.add_argument("--no-judge", action="store_true")
    args = parser.parse_args()
    asyncio.run(
        run_eval_v1(
            dataset_path=args.dataset,
            json_out=args.json_out,
            md_out=args.md_out,
            concurrency=args.concurrency,
            retrieval_only=args.retrieval_only,
            no_judge=args.no_judge,
        )
    )


if __name__ == "__main__":
    main()
