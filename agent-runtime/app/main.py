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

from fastapi.responses import PlainTextResponse, StreamingResponse
from app import config
from app.agent import run_agent, run_agent_stream
from app.events import event_processor
from app.graph import run_graph, run_graph_stream
from app.graph.workflow import llm as graph_llm
from app.mcp_client import mcp_client
from app.metrics import metrics

logging.basicConfig(level=logging.INFO)

app = FastAPI(title="agent-runtime", version="0.1.0")

BUSINESS_API_BASE = config.BUSINESS_API_BASE  # Phase 6 容器化后改为服务名

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


@app.get("/api/mcp-tools")
async def list_mcp_tools(refresh: bool = False):
    """查看当前已连接的 Java MCP Server 暴露的业务工具清单"""
    tools = await mcp_client.list_tools(force_refresh=refresh)
    return {
        "mcp_server_url": mcp_client.url,
        "enabled": mcp_client.enabled,
        "count": len(tools),
        "tools": sorted(tools.keys()),
    }


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
    result = await run_graph(req.session_id, req.message)
    return {
        "reply": result["content"],
        "route": result["route"],
        "agents_used": result["agents_used"],
        "citations": result.get("citations") or [],
        "tool_rounds": result["tool_rounds"],
        "handoff_requested": result["handoff_requested"],
        "degraded": result.get("degraded", False),
        "blocked_by_guardrail": result.get("blocked_by_guardrail"),
        "pending_confirmation": result.get("pending_confirmation"),
        "elapsed_s": result["elapsed_s"],
        "usage": result["usage"],
    }

@app.post("/api/agent-chat/stream")
async def agent_chat_stream(req: ChatRequest):
    """SSE 事件流:前端用 fetch + ReadableStream 消费(EventSource 只支持 GET)"""
    async def sse():
        try:
            async for ev in run_graph_stream(req.session_id, req.message):
                yield f"data: {json.dumps(ev, ensure_ascii=False)}\n\n"
        except Exception as exc:
            yield f"data: {json.dumps({'type': 'error', 'message': str(exc)}, ensure_ascii=False)}\n\n"

    return StreamingResponse(sse(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache",
                                      "X-Accel-Buffering": "no"})  # 防将来 nginx 缓冲整个流


# ── Phase 5 S3：RocketMQ 异步事件接收与主动播报通知接口 ─────────

class OrderStatusEventRequest(BaseModel):
    eventId: str = Field(min_length=1, max_length=128)
    orderNo: str = Field(min_length=1, max_length=64)
    userId: int = 10001
    productId: int
    productName: str
    fromStatus: int
    toStatus: int
    toStatusText: str
    totalAmountCents: int
    occurredAt: str = ""


class ProductChangedEventRequest(BaseModel):
    eventId: str = Field(min_length=1, max_length=128)
    productId: int
    action: str
    name: str
    category: str
    description: str = ""
    priceCents: int = 0
    stock: int = 0
    status: int = 1
    occurredAt: str = ""


@app.post("/api/internal/events/order-status")
async def on_order_status_event(req: OrderStatusEventRequest):
    """接收 Java RocketMQ 转发的订单状态变更事件并生成主动播报通知。"""
    return await event_processor.handle_order_status_event(req.model_dump())


@app.post("/api/internal/events/product-sync")
async def on_product_sync_event(req: ProductChangedEventRequest):
    """接收 Java RocketMQ 转发的商品变更/下架事件并增量更新单条商品向量。"""
    return await event_processor.handle_product_sync_event(req.model_dump())


@app.get("/api/notifications")
async def get_notifications(user_id: int = 10001, limit: int = 10):
    """拉取用户待查看的订单状态主动播报通知列表。"""
    items = await event_processor.list_notifications(user_id=user_id, limit=limit)
    return {"user_id": user_id, "count": len(items), "items": items}


@app.post("/api/notifications/ack")
async def ack_notifications(user_id: int = 10001):
    """清空已读通知。"""
    cleared = await event_processor.clear_notifications(user_id=user_id)
    return {"user_id": user_id, "cleared": cleared}


# ── Phase 5 S4：监控指标（JSON + Prometheus 文本格式）─────────

@app.get("/api/metrics")
async def get_metrics_json():
    """运行时稳定性、安全拦截与熔断状态 JSON 指标接口。"""
    return metrics.snapshot(graph_llm.breaker.snapshot())


@app.get("/metrics", response_class=PlainTextResponse)
async def get_metrics_prometheus():
    """Prometheus 标准抓取端点（text/plain）。"""
    return PlainTextResponse(
        metrics.to_prometheus(graph_llm.breaker.snapshot()),
        media_type="text/plain; version=0.0.4; charset=utf-8",
    )