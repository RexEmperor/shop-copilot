"""DeepSeek 统一客户端:超时 / 指数退避重试 / 耗时与 token 记录。

约定:项目中所有 LLM 调用必须经由本 client,
便于统一做成本统计与降级;Phase 1 接入 Langfuse 全链路追踪。
"""
import asyncio
import logging
import os
import time
import json

import httpx
from dotenv import load_dotenv

load_dotenv()  # 读取 agent-runtime/.env(密钥不入库)

logger = logging.getLogger("llm")

API_URL = "https://api.deepseek.com/chat/completions"
DEFAULT_MODEL = "deepseek-chat"


class DeepSeekClient:
    def __init__(self, api_key: str | None = None, model: str = DEFAULT_MODEL,
                 timeout: float = 30.0, max_retries: int = 2):
        self.api_key = api_key or os.environ["DEEPSEEK_API_KEY"]
        self.model = model
        self.timeout = timeout
        self.max_retries = max_retries  # 首次失败之外的额外重试次数

    async def chat(self, messages: list[dict], tools: list[dict] | None = None) -> dict:
        payload = {"model": self.model, "messages": messages}
        if tools:
            payload["tools"] = tools
        headers = {"Authorization": f"Bearer {self.api_key}"}
        last_err = None
        for attempt in range(1, self.max_retries + 2):
            start = time.perf_counter()
            try:
                async with httpx.AsyncClient(timeout=self.timeout) as client:
                    resp = await client.post(API_URL, headers=headers, json=payload)
                    resp.raise_for_status()
                data = resp.json()
                elapsed = time.perf_counter() - start
                logger.info("deepseek ok: attempt=%s elapsed=%.2fs usage=%s",
                            attempt, elapsed, data.get("usage", {}))
                msg = data["choices"][0]["message"]
                tool_calls = []
                for tc in msg.get("tool_calls") or []:
                    try:
                        args = json.loads(tc["function"]["arguments"] or "{}")
                    except json.JSONDecodeError:
                        args = {}             # 模型偶发吐坏 JSON,兜住
                    tool_calls.append({"id": tc["id"],
                                       "name": tc["function"]["name"],
                                       "args": args})
                return {"content": msg.get("content"),
                        "raw_assistant_message": msg,   # 协议要求原样回传,存着备用
                        "tool_calls": tool_calls,
                        "model": self.model,
                        "elapsed_s": round(elapsed, 2),
                        "usage": data.get("usage", {})}
            except (httpx.HTTPError, KeyError, IndexError) as exc:
                last_err = exc
                backoff = 0.5 * 2 ** (attempt - 1)
                logger.warning("deepseek attempt %s failed: %s → retry in %.1fs",
                               attempt, exc, backoff)
                await asyncio.sleep(backoff)
        raise RuntimeError(f"DeepSeek 调用失败(已重试 {self.max_retries} 次): {last_err}")

    async def chat_stream(self, messages: list[dict], tools: list[dict] | None = None):
        """流式对话:逐块产出事件。
        {"type": "delta", "content"}               回答文本增量
        {"type": "tool_calls", "tool_calls": [...]} 本轮聚合完成的全部工具调用
        {"type": "done", "usage"}                  结束
        取舍:流式不做失败重试(流中断后的断点续传是 Phase 5 课题);
        usage 需显式开启 stream_options,否则 DeepSeek 不在流里返回。
        """
        payload = {"model": self.model, "messages": messages, "stream": True,
                   "stream_options": {"include_usage": True}}
        if tools:
            payload["tools"] = tools
        headers = {"Authorization": f"Bearer {self.api_key}"}
        content_parts: list[str] = []
        tc_acc: dict[int, dict] = {}          # 工具调用增量按 index 聚合
        usage = {}
        start = time.perf_counter()

        async with httpx.AsyncClient(timeout=self.timeout) as client:
            async with client.stream("POST", API_URL, headers=headers, json=payload) as resp:
                resp.raise_for_status()
                async for line in resp.aiter_lines():
                    if not line.startswith("data:"):
                        continue
                    data = line[5:].strip()
                    if data == "[DONE]":
                        break
                    chunk = json.loads(data)
                    if chunk.get("usage"):            # include_usage 的尾块
                        usage = chunk["usage"]
                    choices = chunk.get("choices") or []
                    if not choices:                   # usage 尾块 choices 为空,跳过
                        continue
                    delta = choices[0].get("delta") or {}
                    if delta.get("content"):
                        content_parts.append(delta["content"])
                        yield {"type": "delta", "content": delta["content"]}
                    for tc in delta.get("tool_calls") or []:
                        idx = tc.get("index", 0)
                        acc = tc_acc.setdefault(idx, {"id": "", "name": "", "arguments": ""})
                        if tc.get("id"):
                            acc["id"] = tc["id"]
                        fn = tc.get("function") or {}
                        acc["name"] += fn.get("name") or ""
                        acc["arguments"] += fn.get("arguments") or ""

        logger.info("deepseek stream done: elapsed=%.2fs usage=%s",
                    time.perf_counter() - start, usage)
        if tc_acc:
            calls = []
            for acc in tc_acc.values():
                try:
                    args = json.loads(acc["arguments"] or "{}")
                except json.JSONDecodeError:
                    args = {}
                calls.append({"id": acc["id"], "name": acc["name"], "args": args})
            yield {"type": "tool_calls", "tool_calls": calls}
        yield {"type": "done", "usage": usage}