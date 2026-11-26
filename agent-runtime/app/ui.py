"""Gradio 聊天页:本地可视化 demo(LangGraph 多 Agent SSE 事件消费者)。"""
import json
import uuid

import gradio as gr

from app.graph import run_graph_stream
from app.graph.workers import ROLE_LABELS


def _format_plan(route: str, plan: list[str]) -> str:
    if plan:
        return " → ".join(ROLE_LABELS.get(r, r) for r in plan)
    return ROLE_LABELS.get(route, route)


async def respond(message: str, history: list, state: dict):
    """流式处理:路由、专家协作与工具事件写进轨迹框，最终回答逐字打进聊天区。"""
    trace_text = ""
    reply = ""
    history = history + [{"role": "user", "content": message}]

    async for ev in run_graph_stream(state["session_id"], message):
        ev_type = ev.get("type")
        if ev_type == "route":
            plan_desc = _format_plan(ev.get("route", ""), ev.get("plan") or [])
            reason = ev.get("reason") or ""
            trace_text += f"🧭 路由决策：{plan_desc}" + (f"（{reason}）" if reason else "") + "\n"
            yield "", history, trace_text
        elif ev_type == "agent_status":
            label = ev.get("label") or ev.get("agent")
            if ev.get("status") == "start":
                trace_text += f"🤖 {label} 处理中…\n"
                yield "", history, trace_text
        elif ev_type == "tool_status":
            # 若模型在调工具前先吐了过渡语，清空聊天区以免和最终回答拼在一起
            reply = ""
            args_str = json.dumps(ev.get("args") or {}, ensure_ascii=False)
            trace_text += f"🔧 {ev['name']}({args_str})\n"
            yield "", history, trace_text
        elif ev_type == "tool_result":
            preview = str(ev.get("output") or "")
            if len(preview) > 180:
                preview = preview[:180] + "…"
            trace_text += f"✅ {ev['name']} → {preview}\n"
            yield "", history, trace_text
        elif ev_type == "delta":
            reply += ev["content"]
            yield "", history + [{"role": "assistant", "content": reply}], trace_text
        elif ev_type == "final":
            reply = ev.get("content") or reply
            elapsed = ev.get("elapsed_s")
            tokens = (ev.get("usage") or {}).get("total_tokens")
            trace_text += f"🏁 完成（耗时 {elapsed}s / tokens {tokens or 0}）\n"
            yield "", history + [{"role": "assistant", "content": reply}], trace_text


def new_session() -> dict:
    return {"session_id": f"ui-{uuid.uuid4().hex[:8]}"}


with gr.Blocks(title="智购助手") as demo:
    state = gr.State(new_session)
    gr.Markdown("## 🛒 智购助手\nLangGraph 多 Agent 协作 × Spring AI MCP 电商导购平台")
    chatbot = gr.Chatbot(height=420)
    msg = gr.Textbox(
        placeholder="试试问：'有什么电子产品？' 或 '查下订单 SO202611240002 并推荐款 200 元左右的耳机'",
        autofocus=True,
    )
    tools = gr.Textbox(label="多 Agent 路由与工具轨迹", lines=7, interactive=False)
    clear = gr.ClearButton([msg, chatbot, tools], value="新会话")

    msg.submit(respond, [msg, chatbot, state], [msg, chatbot, tools])


if __name__ == "__main__":
    demo.launch(server_name="127.0.0.1", server_port=7860, show_error=True)
