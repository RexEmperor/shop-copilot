"""agent-runtime 入口:FastAPI 应用"""
import logging
import json
from datetime import datetime

import httpx
from fastapi import FastAPI
from pydantic import BaseModel, Field

from app.chat import append_message, build_messages
from app.llm.client import DeepSeekClient

from app.chat import append_message, build_messages, get_history

from app.agent import run_agent

from fastapi.responses import StreamingResponse
from app.agent import run_agent, run_agent_stream

logging.basicConfig(level=logging.INFO)

app = FastAPI(title="agent-runtime", version="0.1.0")

BUSINESS_API_BASE = "http://localhost:8080"  # Phase 6 容器化后改为服务名

llm = DeepSeekClient()


@app.get("/api/hello")
async def hello(name: str = "world"):
    """调用 Java 业务服务,验证双语言链路"""
    async with httpx.AsyncClient(timeout=5.0) as client:
        resp = await client.get(f"{BUSINESS_API_BASE}/api/hello", params={"name": name})
        resp.raise_for_status()
        java_data = resp.json()

    return {
        "service": "agent-runtime",
        "java_says": java_data["message"],
        "timestamp": datetime.now().isoformat(),
    }


@app.get("/api/llm-check")
async def llm_check(prompt: str = "用一句话介绍你自己"):
    """LLM 链路冒烟检查:DeepSeek 连通性 + 耗时 + token 用量(Phase 0 验收项)"""
    return await llm.chat([{"role": "user", "content": prompt}])


class ChatRequest(BaseModel):
    session_id: str = Field(min_length=8, max_length=64)
    message: str = Field(min_length=1, max_length=2000)


@app.post("/api/chat")
async def chat(req: ChatRequest):
    # 顺序敏感!必须先读历史(缓存空则触发 MySQL 回源回填),
    # 再写用户消息。否则新消息会抢先占住空缓存,
    # 让回源逻辑被"非空假象"绕过(accept-s2-01 事故根因)。
    await get_history(req.session_id)
    await append_message(req.session_id, "user", req.message)
    messages = await build_messages(req.session_id)
    result = await llm.chat(messages)
    await append_message(req.session_id, "assistant", result["content"],
                         model=result["model"], usage=result["usage"],
                         elapsed_ms=int(result["elapsed_s"] * 1000))
    return {"reply": result["content"], "elapsed_s": result["elapsed_s"],
            "usage": result["usage"]}

@app.post("/api/agent-chat")
async def agent_chat(req: ChatRequest):
    result = await run_agent(req.session_id, req.message)
    return {"reply": result["content"], "tool_rounds": result["tool_rounds"],
            "elapsed_s": result["elapsed_s"], "usage": result["usage"]}

@app.post("/api/agent-chat/stream")
async def agent_chat_stream(req: ChatRequest):
    """SSE 事件流:前端用 fetch + ReadableStream 消费(EventSource 只支持 GET)"""
    async def sse():
        try:
            async for ev in run_agent_stream(req.session_id, req.message):
                yield f"data: {json.dumps(ev, ensure_ascii=False)}\n\n"
        except Exception as exc:
            yield f"data: {json.dumps({'type': 'error', 'message': str(exc)}, ensure_ascii=False)}\n\n"

    return StreamingResponse(sse(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache",
                                      "X-Accel-Buffering": "no"})  # 防将来 nginx 缓冲整个流