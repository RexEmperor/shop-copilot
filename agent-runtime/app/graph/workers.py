"""领域专家 ReAct 执行器、动态类目枚举注入、闲聊、多专家汇总与降级兜底节点。"""
import copy
import json
import logging
import time

from langfuse import observe

from app import config
from app.graph.state import (
    AgentState,
    MAX_CONSECUTIVE_TOOL_FAILURES,
    MAX_WORKER_TOOL_ROUNDS,
    ROLE_TOOL_ALLOWLIST,
    emit_event,
    merge_usage,
)
from app.llm.client import DeepSeekClient
from app.mcp_client import mcp_client
from app.prompts import load_prompt
from app.tools import all_schemas, execute

logger = logging.getLogger("graph.workers")

_CATEGORY_CACHE_TTL_S = 120.0
_category_cache: tuple[float, list[str]] | None = None

ROLE_LABELS = {
    "guide": "导购专员",
    "order_aftersale": "订单售后专员",
    "product_qa": "商品问答专员",
    "chitchat": "闲聊助手",
    "synthesize": "汇总专员",
    "fallback": "兜底服务",
    "handoff": "人工客服转接",
}


def invalidate_category_cache() -> None:
    global _category_cache
    _category_cache = None


async def fetch_on_sale_categories() -> list[str]:
    """拉取店铺实时在售类目并短时缓存，用于前置注入 search_products 的 category 枚举。"""
    global _category_cache
    now = time.monotonic()
    if _category_cache is not None and (now - _category_cache[0]) < _CATEGORY_CACHE_TTL_S:
        return list(_category_cache[1])

    if not await mcp_client.has_tool("list_categories"):
        return []
    try:
        raw = await mcp_client.call_tool("list_categories", {})
        payload = json.loads(raw)
        if payload.get("success") and isinstance(payload.get("data"), list):
            categories = [
                item["category"]
                for item in payload["data"]
                if isinstance(item, dict) and item.get("category")
            ]
            _category_cache = (now, categories)
            return list(categories)
    except Exception as exc:
        logger.warning("Failed to prefetch on-sale categories: %r", exc)
    return []


def inject_category_enum(tool_schemas: list[dict], categories: list[str]) -> list[dict]:
    """将店铺真实在售类目注入 search_products 的 category 参数 enum 与描述。"""
    if not categories:
        return tool_schemas
    enriched = copy.deepcopy(tool_schemas)
    for schema in enriched:
        fn = schema.get("function") or {}
        if fn.get("name") != "search_products":
            continue
        props = (fn.get("parameters") or {}).get("properties") or {}
        cat_prop = props.get("category")
        if isinstance(cat_prop, dict):
            cat_prop["enum"] = list(categories)
            cat_desc = cat_prop.get("description") or "商品分类名称"
            cat_prop["description"] = f"{cat_desc}（当前在售合法类目：{', '.join(categories)}）"
    return enriched


def is_tool_system_failure(output: str) -> bool:
    """区分系统/网络故障与正常业务拦截（404 下架 / 409 非法状态属于正常业务结果，不计为故障）。"""
    if not output:
        return True
    if output.startswith(("工具执行失败", "错误:未注册的工具", "外部业务工具")):
        return True
    try:
        parsed = json.loads(output)
        if isinstance(parsed, dict) and parsed.get("success") is False:
            return int(parsed.get("code", 0)) >= 500
    except json.JSONDecodeError:
        pass
    return False


async def _chat_maybe_stream(
    llm: DeepSeekClient,
    messages: list[dict],
    *,
    tools: list[dict] | None = None,
    stream_tokens: bool = False,
) -> dict:
    """统一封装非流式与流式调用；流式模式下实时推送 delta 事件并返回同构结果字典。"""
    if not stream_tokens or not hasattr(llm, "chat_stream"):
        return await llm.chat(messages, tools=tools)

    content_parts: list[str] = []
    tool_calls: list[dict] = []
    usage: dict = {}
    async for ev in llm.chat_stream(messages, tools=tools):
        ev_type = ev.get("type")
        if ev_type == "delta" and ev.get("content"):
            content_parts.append(ev["content"])
            emit_event({"type": "delta", "content": ev["content"]})
        elif ev_type == "tool_calls":
            tool_calls = ev.get("tool_calls") or []
        elif ev_type == "done":
            usage = ev.get("usage") or {}

    content = "".join(content_parts)
    raw_msg: dict = {"role": "assistant", "content": content or None}
    if tool_calls:
        raw_msg["tool_calls"] = [
            {
                "id": c["id"],
                "type": "function",
                "function": {
                    "name": c["name"],
                    "arguments": json.dumps(c["args"], ensure_ascii=False),
                },
            }
            for c in tool_calls
        ]
    return {
        "content": content,
        "raw_assistant_message": raw_msg,
        "tool_calls": tool_calls,
        "model": getattr(llm, "model", None),
        "usage": usage,
    }


def _build_worker_messages(role: str, state: AgentState) -> list[dict]:
    system_prompt = load_prompt(role, config.PROMPT_VERSION)
    prior_outputs = state.get("agent_outputs") or []
    if prior_outputs:
        summary_lines = [
            f"- {ROLE_LABELS.get(item['agent'], item['agent'])}已处理结果：{item['reply']}"
            for item in prior_outputs
        ]
        system_prompt = (
            f"{system_prompt}\n\n"
            "【协同上下文】前序专家已完成用户请求中的另一部分，你只需聚焦本领域负责的部分，"
            "避免重复解答前序内容：\n" + "\n".join(summary_lines)
        )
    history = state.get("history") or [{"role": "user", "content": state["user_message"]}]
    return [{"role": "system", "content": system_prompt}, *history]


@observe(name="graph-worker")
async def run_worker_node(role: str, state: AgentState, llm: DeepSeekClient) -> dict:
    """通用领域专家执行器：按白名单绑定工具并执行受限 ReAct 循环。"""
    label = ROLE_LABELS.get(role, role)
    emit_event({"type": "agent_status", "agent": role, "label": label, "status": "start"})

    allowed = ROLE_TOOL_ALLOWLIST.get(role, set())
    tool_schemas = await all_schemas(
        allowed_names=allowed, include_local=False, include_mcp=bool(allowed)
    )
    if role == "guide" and tool_schemas:
        categories = await fetch_on_sale_categories()
        tool_schemas = inject_category_enum(tool_schemas, categories)

    messages = _build_worker_messages(role, state)
    usage = dict(state.get("usage") or {})
    tool_rounds = int(state.get("tool_rounds", 0))
    tool_trace: list[dict] = []
    consecutive_failures = 0
    tool_failed = False
    reply = ""

    plan = state.get("plan") or [role]
    # 仅单域直出时实时吐字；跨域协作时由最后的 synthesize 节点统一流式吐字
    stream_this_worker = bool(state.get("stream_tokens")) and len(plan) == 1

    for _ in range(MAX_WORKER_TOOL_ROUNDS):
        result = await _chat_maybe_stream(
            llm, messages, tools=tool_schemas or None, stream_tokens=stream_this_worker
        )
        usage = merge_usage(usage, result.get("usage"))
        tool_calls = result.get("tool_calls") or []
        if not tool_calls:
            reply = (result.get("content") or "").strip()
            break

        tool_rounds += 1
        raw_msg = result["raw_assistant_message"]
        messages.append({
            "role": "assistant",
            "content": raw_msg.get("content") or "",
            "tool_calls": raw_msg["tool_calls"],
        })

        for call in tool_calls:
            emit_event({
                "type": "tool_status",
                "agent": role,
                "name": call["name"],
                "args": call["args"],
            })
            output = await execute(
                call["name"], call["args"], context={"session_id": state["session_id"]}
            )
            emit_event({
                "type": "tool_result",
                "agent": role,
                "name": call["name"],
                "output": output,
            })
            tool_trace.append({"name": call["name"], "args": call["args"], "output": output})
            messages.append({"role": "tool", "tool_call_id": call["id"], "content": output})

            if is_tool_system_failure(output):
                consecutive_failures += 1
            else:
                consecutive_failures = 0

        if consecutive_failures >= MAX_CONSECUTIVE_TOOL_FAILURES:
            tool_failed = True
            logger.warning(
                "Worker %s hit %s consecutive tool failures, routing to fallback",
                role, consecutive_failures,
            )
            break

    if not reply and not tool_failed:
        reply = "抱歉，这次查询轮次较多暂未整理出完整结果，您可以换一种更具体的描述再试试。"
        if stream_this_worker:
            emit_event({"type": "delta", "content": reply})

    emit_event({
        "type": "agent_status",
        "agent": role,
        "label": label,
        "status": "done",
        "failed": tool_failed,
    })

    outputs = list(state.get("agent_outputs") or [])
    outputs.append({
        "agent": role,
        "reply": reply,
        "tool_trace": tool_trace,
        "failed": tool_failed,
    })
    next_index = int(state.get("step_index", 0)) + 1

    patch: dict = {
        "agent_outputs": outputs,
        "step_index": next_index,
        "tool_rounds": tool_rounds,
        "tool_failed": tool_failed,
        "failed_agent": role if tool_failed else None,
        "usage": usage,
    }
    if not tool_failed and next_index >= len(plan) and len(outputs) == 1:
        patch["final_reply"] = reply
    return patch


@observe(name="graph-chitchat")
async def chitchat_node(state: AgentState, llm: DeepSeekClient) -> dict:
    """闲聊节点：零工具 Schema 开销，直接响应。"""
    emit_event({"type": "agent_status", "agent": "chitchat", "label": ROLE_LABELS["chitchat"], "status": "start"})
    system_prompt = load_prompt("chitchat", config.PROMPT_VERSION)
    history = state.get("history") or [{"role": "user", "content": state["user_message"]}]
    result = await _chat_maybe_stream(
        llm,
        [{"role": "system", "content": system_prompt}, *history],
        tools=None,
        stream_tokens=bool(state.get("stream_tokens")),
    )
    reply = (result.get("content") or "您好！请问有什么可以帮您？").strip()
    emit_event({"type": "agent_status", "agent": "chitchat", "label": ROLE_LABELS["chitchat"], "status": "done"})
    return {
        "final_reply": reply,
        "usage": merge_usage(state.get("usage"), result.get("usage")),
    }


@observe(name="graph-synthesize")
async def synthesize_node(state: AgentState, llm: DeepSeekClient) -> dict:
    """多专家协同汇总节点：把 2 个专家的查询结论整合成一段自然连贯的回复。"""
    emit_event({"type": "agent_status", "agent": "synthesize", "label": ROLE_LABELS["synthesize"], "status": "start"})
    outputs = state.get("agent_outputs") or []
    parts = [
        f"【{ROLE_LABELS.get(item['agent'], item['agent'])}】\n{item['reply']}"
        for item in outputs
        if item.get("reply")
    ]
    messages = [
        {
            "role": "system",
            "content": (
                "你是“智购助手”的回复汇总员。请将多位业务专家的查询结果整合为一段条理清晰、"
                "自然友好的中文答复给用户。严格保留原专家给出的所有价格、库存、订单号、状态和金额，"
                "禁止修改数字或编造额外信息。"
            ),
        },
        {
            "role": "user",
            "content": f"用户原问题：{state['user_message']}\n\n各专家处理结果：\n" + "\n\n".join(parts),
        },
    ]
    result = await _chat_maybe_stream(
        llm, messages, tools=None, stream_tokens=bool(state.get("stream_tokens"))
    )
    reply = (result.get("content") or "\n\n".join(parts)).strip()
    emit_event({"type": "agent_status", "agent": "synthesize", "label": ROLE_LABELS["synthesize"], "status": "done"})
    return {
        "final_reply": reply,
        "usage": merge_usage(state.get("usage"), result.get("usage")),
    }


@observe(name="graph-fallback")
async def fallback_node(state: AgentState) -> dict:
    """兜底节点：区分“用户主动要人工”与“工具连续失败降级建议（不强行切走会话）”。"""
    emit_event({"type": "agent_status", "agent": "fallback", "label": ROLE_LABELS["fallback"], "status": "start"})
    if state.get("handoff_requested"):
        reply = (
            "好的，已为您记录转人工客服请求。人工客服将尽快为您接入处理；"
            "您可以先把订单号或具体问题描述发给我，方便客服同学更快核实。"
        )
        handoff = True
    else:
        failed_role = ROLE_LABELS.get(state.get("failed_agent") or "", "业务查询")
        prior_ok = [
            item["reply"]
            for item in (state.get("agent_outputs") or [])
            if not item.get("failed") and item.get("reply")
        ]
        prefix = (prior_ok[0] + "\n\n") if prior_ok else ""
        reply = (
            f"{prefix}抱歉，当前{failed_role}服务暂时遇到波动，未能获取到准确数据（我不会凭空猜测结果）。"
            "建议您稍后重试，或回复“转人工”由人工客服为您继续处理。"
        )
        handoff = False

    if state.get("stream_tokens"):
        emit_event({"type": "delta", "content": reply})
    emit_event({"type": "agent_status", "agent": "fallback", "label": ROLE_LABELS["fallback"], "status": "done"})
    return {
        "final_reply": reply,
        "handoff_requested": handoff,
    }
