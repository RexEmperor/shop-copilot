"""Supervisor / Router 节点：识别单域或跨域意图并生成受限执行计划。"""
import json
import logging
import re

from langfuse import observe

from app import config
from app.graph.state import (
    AgentState,
    MAX_PLAN_STEPS,
    ROUTER_HISTORY_LIMIT,
    VALID_ROUTES,
    WORKER_ROLES,
    emit_event,
    merge_usage,
)
from app.llm.client import DeepSeekClient
from app.prompts import load_prompt

logger = logging.getLogger("graph.router")

_JSON_BLOCK_RE = re.compile(r"\{.*\}", re.DOTALL)


def parse_route_decision(raw_text: str | None) -> tuple[str, list[str], str]:
    """容错解析路由 JSON；模型偶发输出非标准文本时安全降级，绝不抛 500。"""
    if not raw_text:
        return "guide", ["guide"], "empty_router_output"

    text = raw_text.strip()
    match = _JSON_BLOCK_RE.search(text)
    if not match:
        logger.warning("Router output missing JSON object, fallback to guide: %r", text[:120])
        return "guide", ["guide"], "non_json_fallback"

    try:
        data = json.loads(match.group(0))
    except json.JSONDecodeError:
        logger.warning("Router output invalid JSON, fallback to guide: %r", text[:120])
        return "guide", ["guide"], "invalid_json_fallback"

    route = str(data.get("route", "")).strip()
    reason = str(data.get("reason", "")).strip()[:60]
    raw_plan = data.get("plan") if isinstance(data.get("plan"), list) else []

    if route not in VALID_ROUTES:
        route = "guide"

    if route in {"chitchat", "handoff"}:
        return route, [], reason

    # 清洗跨域计划：仅保留合法专家角色、去重、最多 2 步
    plan: list[str] = []
    for item in raw_plan:
        role = str(item).strip()
        if role in WORKER_ROLES and role not in plan:
            plan.append(role)
        if len(plan) >= MAX_PLAN_STEPS:
            break

    if not plan:
        plan = [route]
    else:
        route = plan[0]
    return route, plan, reason


@observe(name="graph-router")
async def route_node(state: AgentState, llm: DeepSeekClient) -> dict:
    """仅携带最近若干轮历史做轻量意图分类，不附带任何工具 schema。"""
    system_prompt = load_prompt("router", config.PROMPT_VERSION)
    recent = (state.get("history") or [])[-ROUTER_HISTORY_LIMIT:]
    messages = [{"role": "system", "content": system_prompt}, *recent]

    result = await llm.chat(messages, tools=None)
    route, plan, reason = parse_route_decision(result.get("content"))
    logger.info("Router decision: session=%s route=%s plan=%s reason=%s",
                state.get("session_id"), route, plan, reason)
    emit_event({
        "type": "route",
        "route": route,
        "plan": plan,
        "reason": reason,
    })
    return {
        "route": route,
        "route_reason": reason,
        "plan": plan,
        "step_index": 0,
        "agent_outputs": [],
        "tool_rounds": 0,
        "tool_failed": False,
        "failed_agent": None,
        "handoff_requested": route == "handoff",
        "usage": merge_usage(state.get("usage"), result.get("usage")),
    }
