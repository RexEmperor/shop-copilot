"""多 Agent 共享状态定义、工具权限白名单与 token 聚合工具。"""
from typing import TypedDict

from langgraph.config import get_stream_writer

VALID_ROUTES = frozenset({"guide", "order_aftersale", "product_qa", "chitchat", "handoff"})
WORKER_ROLES = frozenset({"guide", "order_aftersale", "product_qa"})

MAX_PLAN_STEPS = 2
MAX_WORKER_TOOL_ROUNDS = 4
MAX_CONSECUTIVE_TOOL_FAILURES = 2
ROUTER_HISTORY_LIMIT = 6

# 最小权限工具白名单：Python 编排层按 Agent 角色隔离工具访问权限
ROLE_TOOL_ALLOWLIST: dict[str, set[str]] = {
    "guide": {"list_categories", "search_products", "get_product_detail"},
    "order_aftersale": {
        "list_orders",
        "get_order_detail",
        "create_aftersale",
        "list_aftersales",
    },
    "product_qa": {"get_product_detail"},
    "chitchat": set(),
    "handoff": set(),
}


class AgentState(TypedDict, total=False):
    session_id: str
    user_message: str
    history: list[dict]
    route: str
    route_reason: str
    plan: list[str]
    step_index: int
    agent_outputs: list[dict]
    tool_rounds: int
    tool_failed: bool
    failed_agent: str | None
    handoff_requested: bool
    stream_tokens: bool
    final_reply: str
    usage: dict


def emit_event(event: dict) -> None:
    """在 LangGraph stream_mode='custom' 下实时推送事件；非流式 ainvoke 时安全静默。"""
    try:
        writer = get_stream_writer()
        if writer is not None:
            writer(event)
    except RuntimeError:
        pass


def merge_usage(acc: dict | None, delta: dict | None) -> dict:
    """累加同一轮多节点调用的 prompt/completion/total token 计数。"""
    base = dict(acc or {})
    if not delta:
        return base
    for key in ("prompt_tokens", "completion_tokens", "total_tokens"):
        base[key] = int(base.get(key, 0)) + int(delta.get(key, 0))
    return base
