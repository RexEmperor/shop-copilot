"""agent-runtime 入口:FastAPI 应用"""
from datetime import datetime

import httpx
from fastapi import FastAPI

app = FastAPI(title="agent-runtime", version="0.1.0")

# Phase 6 容器化后,这里会改成容器服务名;现在先用配置常量
BUSINESS_API_BASE = "http://localhost:8080"


@app.get("/api/hello")
async def hello(name: str = "world"):
    """调用 Java 业务服务,验证双语言链路"""
    async with httpx.AsyncClient(timeout=5.0) as client:
        resp = await client.get(f"{BUSINESS_API_BASE}/api/hello", params={"name": name})
        resp.raise_for_status()          # 非 2xx 直接抛错——快速失败
        java_data = resp.json()

    return {
        "service": "agent-runtime",
        "java_says": java_data["message"],
        "timestamp": datetime.now().isoformat(),
    }