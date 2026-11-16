"""agent-runtime 入口:FastAPI 应用"""
import logging
from datetime import datetime

import httpx
from fastapi import FastAPI
from pydantic import BaseModel, Field

from app.chat import append_message, build_messages
from app.llm.client import DeepSeekClient

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
    """多轮对话:历史存 Redis,同一 session_id 跨请求共享上下文"""
    await append_message(req.session_id, "user", req.message)
    messages = await build_messages(req.session_id)
    result = await llm.chat(messages)
    await append_message(req.session_id, "assistant", result["content"])
    return {"reply": result["content"], "elapsed_s": result["elapsed_s"],
            "usage": result["usage"]}