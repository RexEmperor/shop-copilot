"""LangGraph 多 Agent 状态机装配与统一调用入口（同步 + SSE 流式 + 安全护栏与资金二次确认）。"""
import json
import time

from langfuse import get_client, observe, propagate_attributes
from langgraph.graph import END, START, StateGraph

from app import config
from app.chat import append_message, get_history
from app.graph.router import route_node
from app.graph.state import AgentState
from app.graph.workers import chitchat_node, fallback_node, run_worker_node, synthesize_node
from app.guardrails.confirmation import classify_confirmation_reply, confirmation_manager
from app.guardrails.security import check_input_safety, sanitize_output
from app.llm.client import DeepSeekClient
from app.metrics import metrics
from app.tools import execute as execute_tool

llm = DeepSeekClient()


def _route_after_supervisor(state: AgentState) -> str:
    if state.get("llm_degraded"):
        return "fallback"
    route = state.get("route", "guide")
    if route == "chitchat":
        return "chitchat"
    if route in {"handoff", "fallback"}:
        return "fallback"
    plan = state.get("plan") or [route]
    return plan[0]


def _route_after_worker(state: AgentState) -> str:
    if state.get("tool_failed") or state.get("llm_degraded"):
        return "fallback"
    plan = state.get("plan") or []
    step_index = int(state.get("step_index", 0))
    if step_index < len(plan):
        return plan[step_index]
    if len(state.get("agent_outputs") or []) > 1:
        return "synthesize"
    return END


def build_graph(llm_client: DeepSeekClient | None = None):
    """构建并编译多 Agent 协作 StateGraph。"""
    client = llm_client or llm
    builder = StateGraph(AgentState)

    async def _router(state: AgentState) -> dict:
        return await route_node(state, client)

    async def _guide(state: AgentState) -> dict:
        return await run_worker_node("guide", state, client)

    async def _order_aftersale(state: AgentState) -> dict:
        return await run_worker_node("order_aftersale", state, client)

    async def _product_qa(state: AgentState) -> dict:
        return await run_worker_node("product_qa", state, client)

    async def _chitchat(state: AgentState) -> dict:
        return await chitchat_node(state, client)

    async def _synthesize(state: AgentState) -> dict:
        return await synthesize_node(state, client)

    builder.add_node("router", _router)
    builder.add_node("guide", _guide)
    builder.add_node("order_aftersale", _order_aftersale)
    builder.add_node("product_qa", _product_qa)
    builder.add_node("chitchat", _chitchat)
    builder.add_node("synthesize", _synthesize)
    builder.add_node("fallback", fallback_node)

    builder.add_edge(START, "router")
    builder.add_conditional_edges(
        "router",
        _route_after_supervisor,
        {
            "guide": "guide",
            "order_aftersale": "order_aftersale",
            "product_qa": "product_qa",
            "chitchat": "chitchat",
            "fallback": "fallback",
        },
    )

    worker_targets = {
        "guide": "guide",
        "order_aftersale": "order_aftersale",
        "product_qa": "product_qa",
        "synthesize": "synthesize",
        "fallback": "fallback",
        END: END,
    }
    for worker in ("guide", "order_aftersale", "product_qa"):
        builder.add_conditional_edges(worker, _route_after_worker, worker_targets)

    builder.add_edge("chitchat", END)
    builder.add_edge("synthesize", END)
    builder.add_edge("fallback", END)

    return builder.compile()


def _format_confirmed_tool_reply(tool_name: str, summary: dict, raw_output: str) -> str:
    try:
        parsed = json.loads(raw_output)
    except json.JSONDecodeError:
        return f"已按您的确认提交操作，系统返回：{raw_output}"

    if isinstance(parsed, dict) and parsed.get("success"):
        data = parsed.get("data") or {}
        if tool_name == "create_aftersale":
            ticket_no = data.get("ticketNo") or "已生成"
            refund_yuan = data.get("refundYuan") or "按订单实付金额核定"
            return (
                f"已为您成功提交售后申请！\n"
                f"- 售后工单号：**{ticket_no}**\n"
                f"- 关联订单号：{summary.get('orderNo')}\n"
                f"- 售后类型：{summary.get('aftersaleType')}\n"
                f"- 退款金额：{refund_yuan} 元\n"
                f"- 当前状态：待审核（商家承诺 48 小时内审核处理）"
            )
        return f"操作已确认并执行成功：{json.dumps(data, ensure_ascii=False)}"

    err_msg = parsed.get("message") if isinstance(parsed, dict) else raw_output
    return (
        f"抱歉，按您的确认提交订单 {summary.get('orderNo', '')} 的售后申请时被业务规则拦截：{err_msg}。"
        "如仍有疑问，可回复“转人工”由人工客服协助核实。"
    )


async def _check_pre_graph_interceptors(session_id: str, user_message: str) -> dict | None:
    """图入口前置护栏：
    1. 敏感词 / Prompt Injection 安全拦截（零 LLM 消耗）；
    2. 资金类操作（create_aftersale）二次确认与幂等执行。
    命中时返回可直接收尾的结果字典；未命中返回 None 进入正常多 Agent 编排。
    """
    safety = check_input_safety(user_message)
    if not safety.allowed:
        return {
            "content": safety.safe_reply,
            "route": "guardrail",
            "plan": [],
            "agents_used": ["guardrail"],
            "citations": [],
            "tool_rounds": 0,
            "handoff_requested": False,
            "degraded": False,
            "blocked_by_guardrail": safety.category,
            "pending_confirmation": None,
            "usage": {},
        }

    pending = await confirmation_manager.get_pending(session_id)
    if pending:
        decision = classify_confirmation_reply(user_message)
        if decision == "cancel":
            await confirmation_manager.clear_pending(session_id)
            return {
                "content": f"好的，已取消订单 {pending['summary'].get('orderNo', '')} 的售后提交，未产生任何操作。",
                "route": "confirmation",
                "plan": [],
                "agents_used": ["confirmation"],
                "citations": [],
                "tool_rounds": 0,
                "handoff_requested": False,
                "degraded": False,
                "blocked_by_guardrail": None,
                "pending_confirmation": None,
                "usage": {},
            }
        if decision == "confirm":
            idem_key = pending["idempotency_key"]
            acquired = await confirmation_manager.acquire_idempotency(idem_key)
            await confirmation_manager.clear_pending(session_id)
            if not acquired:
                return {
                    "content": "该笔售后申请已提交过（系统已做幂等防重拦截，未重复提交），您可直接查询售后进度。",
                    "route": "confirmation",
                    "plan": [],
                    "agents_used": ["confirmation"],
                    "citations": [],
                    "tool_rounds": 0,
                    "handoff_requested": False,
                    "degraded": False,
                    "blocked_by_guardrail": None,
                    "pending_confirmation": None,
                    "usage": {},
                }
            raw_out = await execute_tool(
                pending["tool"], pending["args"], {"session_id": session_id}
            )
            reply = _format_confirmed_tool_reply(pending["tool"], pending["summary"], raw_out)
            await confirmation_manager.save_executed_result(idem_key, reply)
            return {
                "content": reply,
                "route": "confirmation",
                "plan": [],
                "agents_used": ["confirmation"],
                "citations": [],
                "tool_rounds": 1,
                "handoff_requested": False,
                "degraded": False,
                "blocked_by_guardrail": None,
                "pending_confirmation": None,
                "usage": {},
                "_executed_tool": {"name": pending["tool"], "args": pending["args"], "output": raw_out},
            }
    return None


@observe(name="agent-graph")
async def run_graph(
    session_id: str,
    user_message: str,
    *,
    llm_client: DeepSeekClient | None = None,
) -> dict:
    """运行多 Agent 状态机并维护 MySQL + Redis 会话历史顺序契约。"""
    start = time.perf_counter()
    client = llm_client or llm

    with propagate_attributes(session_id=session_id):
        await get_history(session_id)
        await append_message(session_id, "user", user_message)

        intercepted = await _check_pre_graph_interceptors(session_id, user_message)
        if intercepted is not None:
            elapsed_s = round(time.perf_counter() - start, 2)
            reply = sanitize_output(intercepted["content"])
            await append_message(
                session_id,
                "assistant",
                reply,
                model=None,
                usage={},
                elapsed_ms=int(elapsed_s * 1000),
            )
            exec_tool = intercepted.pop("_executed_tool", None)
            intercepted["content"] = reply
            intercepted["elapsed_s"] = elapsed_s
            metrics.record_chat(
                route=intercepted["route"],
                elapsed_s=elapsed_s,
                usage={},
                degraded=False,
                blocked_by_guardrail=intercepted.get("blocked_by_guardrail"),
                executed_confirmation=bool(exec_tool),
            )
            return intercepted

        history = (await get_history(session_id))[-config.HISTORY_MAX_MESSAGES:]

        graph = build_graph(client)
        final_state: AgentState = await graph.ainvoke({
            "session_id": session_id,
            "user_message": user_message,
            "history": history,
            "stream_tokens": False,
            "usage": {},
        })

        elapsed_s = round(time.perf_counter() - start, 2)
        reply = sanitize_output(final_state.get("final_reply") or "抱歉，这次没能完成处理，请稍后重试。")
        usage = final_state.get("usage") or {}

        await append_message(
            session_id,
            "assistant",
            reply,
            model=getattr(client, "model", None),
            usage=usage,
            elapsed_ms=int(elapsed_s * 1000),
        )

        agents_used = [item["agent"] for item in (final_state.get("agent_outputs") or [])]
        if not agents_used and final_state.get("route"):
            agents_used = [final_state["route"]]

        metrics.record_chat(
            route=final_state.get("route", "guide"),
            elapsed_s=elapsed_s,
            usage=usage,
            degraded=bool(final_state.get("llm_degraded", False)),
            pending_confirmation=final_state.get("pending_confirmation"),
        )

        return {
            "content": reply,
            "route": final_state.get("route", "guide"),
            "plan": final_state.get("plan") or [],
            "agents_used": agents_used,
            "citations": final_state.get("citations") or [],
            "tool_rounds": int(final_state.get("tool_rounds", 0)),
            "handoff_requested": bool(final_state.get("handoff_requested", False)),
            "degraded": bool(final_state.get("llm_degraded", False)),
            "blocked_by_guardrail": None,
            "pending_confirmation": final_state.get("pending_confirmation"),
            "elapsed_s": elapsed_s,
            "usage": usage,
        }


async def run_graph_stream(
    session_id: str,
    user_message: str,
    *,
    llm_client: DeepSeekClient | None = None,
):
    """事件流版多 Agent 状态机。
    协议事件：route / agent_status / tool_status / tool_result / citations / confirmation_required / delta / final。
    先持久化 assistant 最终回复，再发 final，保证客户端拿到完成信号时历史已落库。
    """
    start = time.perf_counter()
    client = llm_client or llm

    with get_client().start_as_current_observation(name="agent-graph-stream"):
        with propagate_attributes(session_id=session_id):
            await get_history(session_id)
            await append_message(session_id, "user", user_message)

            intercepted = await _check_pre_graph_interceptors(session_id, user_message)
            if intercepted is not None:
                elapsed_s = round(time.perf_counter() - start, 2)
                reply = sanitize_output(intercepted["content"])
                yield {
                    "type": "route",
                    "route": intercepted["route"],
                    "plan": [],
                    "reason": intercepted.get("blocked_by_guardrail") or "confirmation",
                }
                exec_tool = intercepted.pop("_executed_tool", None)
                if exec_tool:
                    yield {
                        "type": "tool_status",
                        "agent": "confirmation",
                        "name": exec_tool["name"],
                        "args": exec_tool["args"],
                    }
                    yield {
                        "type": "tool_result",
                        "agent": "confirmation",
                        "name": exec_tool["name"],
                        "output": exec_tool["output"],
                    }
                yield {"type": "delta", "content": reply}
                await append_message(
                    session_id,
                    "assistant",
                    reply,
                    model=None,
                    usage={},
                    elapsed_ms=int(elapsed_s * 1000),
                )
                metrics.record_chat(
                    route=intercepted["route"],
                    elapsed_s=elapsed_s,
                    usage={},
                    degraded=False,
                    blocked_by_guardrail=intercepted.get("blocked_by_guardrail"),
                    executed_confirmation=bool(exec_tool),
                )
                yield {
                    "type": "final",
                    **intercepted,
                    "content": reply,
                    "elapsed_s": elapsed_s,
                }
                return

            history = (await get_history(session_id))[-config.HISTORY_MAX_MESSAGES:]

            graph = build_graph(client)
            final_state: AgentState = {}
            async for mode, chunk in graph.astream(
                {
                    "session_id": session_id,
                    "user_message": user_message,
                    "history": history,
                    "stream_tokens": True,
                    "usage": {},
                },
                stream_mode=["custom", "values"],
            ):
                if mode == "custom":
                    yield chunk
                elif mode == "values":
                    final_state = chunk

            elapsed_s = round(time.perf_counter() - start, 2)
            reply = sanitize_output(final_state.get("final_reply") or "抱歉，这次没能完成处理，请稍后重试。")
            usage = final_state.get("usage") or {}

            await append_message(
                session_id,
                "assistant",
                reply,
                model=getattr(client, "model", None),
                usage=usage,
                elapsed_ms=int(elapsed_s * 1000),
            )

            agents_used = [item["agent"] for item in (final_state.get("agent_outputs") or [])]
            if not agents_used and final_state.get("route"):
                agents_used = [final_state["route"]]

            metrics.record_chat(
                route=final_state.get("route", "guide"),
                elapsed_s=elapsed_s,
                usage=usage,
                degraded=bool(final_state.get("llm_degraded", False)),
                pending_confirmation=final_state.get("pending_confirmation"),
            )

            yield {
                "type": "final",
                "content": reply,
                "route": final_state.get("route", "guide"),
                "plan": final_state.get("plan") or [],
                "agents_used": agents_used,
                "citations": final_state.get("citations") or [],
                "tool_rounds": int(final_state.get("tool_rounds", 0)),
                "handoff_requested": bool(final_state.get("handoff_requested", False)),
                "degraded": bool(final_state.get("llm_degraded", False)),
                "blocked_by_guardrail": None,
                "pending_confirmation": final_state.get("pending_confirmation"),
                "usage": usage,
                "elapsed_s": elapsed_s,
            }
