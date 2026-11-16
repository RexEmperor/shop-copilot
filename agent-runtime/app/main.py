"""agent-runtime 入口:FastAPI 应用"""
import logging
from datetime import datetime

import httpx
from fastapi import FastAPI

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
    return await llm.chat(prompt)