"""Gradio 聊天页:本地可视化 demo(SSE 事件的第一个消费者)。

取舍:Gradio 进程直接调 run_agent_stream 函数,不走 HTTP——
开发期图快;生产部署应改为消费 /api/agent-chat/stream(记入 backlog)。
流式路径的 Langfuse 上报也暂缺(backlog)。
"""
import uuid

import gradio as gr

from app.agent import run_agent_stream


async def respond(message: str, history: list, state: dict):
    """流式处理:工具事件写进"工具轨迹"框,回答文本逐字打进聊天区"""
    tools_text = ""
    reply = ""
    history = history + [{"role": "user", "content": message}]
    async for ev in run_agent_stream(state["session_id"], message):
        if ev["type"] == "tool_status":
            tools_text += f"🔧 {ev['name']} 调用中…\n"
        elif ev["type"] == "tool_result":
            tools_text += f"✅ {ev['name']} → {ev['output']}\n"
        elif ev["type"] == "delta":
            reply += ev["content"]
            yield "", history + [{"role": "assistant", "content": reply}], tools_text
    yield "", history + [{"role": "assistant", "content": reply}], tools_text


def new_session() -> dict:
    return {"session_id": f"ui-{uuid.uuid4().hex[:8]}"}


with gr.Blocks(title="智购助手") as demo:
    state = gr.State(new_session)
    gr.Markdown("## 🛒 智购助手\n多 Agent 电商导购 demo")
    chatbot = gr.Chatbot(height=420)          # gradio 6.x:messages 格式已是唯一默认,无需 type 参数
    msg = gr.Textbox(placeholder="想买点什么?直接问,或问'现在几点了'", autofocus=True)
    tools = gr.Textbox(label="工具轨迹", lines=4, interactive=False)
    clear = gr.ClearButton([msg, chatbot, tools], value="新会话")

    msg.submit(respond, [msg, chatbot, state], [msg, chatbot, tools])


if __name__ == "__main__":
    demo.launch(server_name="127.0.0.1", server_port=7860, show_error=True)