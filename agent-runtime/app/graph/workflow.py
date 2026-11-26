"""LangGraph 多 Agent 状态机装配与统一调用入口。"""
import time

from langfuse import observe, propagate_attributes
from langgraph.graph import END, START, StateGraph

from app import config
from app.chat import append_message, get_history
from app.graph.router import route_node
from app.graph.state import AgentState
from app.graph.workers import chitchat_node, fallback_node, run_worker_node, synthesize_node
from app.llm.client import DeepSeekClient

llm = DeepSeekClient()


def _route_after_supervisor(state: AgentState) -> str:
    route = state.get("route", "guide")
    if route == "chitchat":
        return "chitchat"
    if route == "handoff":
        return "fallback"
    plan = state.get("plan") or [route]
    return plan[0]


def _route_after_worker(state: AgentState) -> str:
    if state.get("tool_failed"):
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
        history = (await get_history(session_id))[-config.HISTORY_MAX_MESSAGES:]

        graph = build_graph(client)
        final_state: AgentState = await graph.ainvoke({
            "session_id": session_id,
            "user_message": user_message,
            "history": history,
            "usage": {},
        })

        elapsed_s = round(time.perf_counter() - start, 2)
        reply = final_state.get("final_reply") or "抱歉，这次没能完成处理，请稍后重试。"
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

        return {
            "content": reply,
            "route": final_state.get("route", "guide"),
            "plan": final_state.get("plan") or [],
            "agents_used": agents_used,
            "tool_rounds": int(final_state.get("tool_rounds", 0)),
            "handoff_requested": bool(final_state.get("handoff_requested", False)),
            "elapsed_s": elapsed_s,
            "usage": usage,
        }
