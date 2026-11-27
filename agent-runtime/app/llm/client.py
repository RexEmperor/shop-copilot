"""大模型统一客户端:超时 / 熔断器 / 带抖动退避防重试风暴 / 并发舱壁 / 流中断成本估算兜底。

约定:项目中所有 LLM 调用必须经由本 client,
便于统一做成本统计、熔断保护与规则降级;Langfuse 全链路追踪自动挂载。
"""
import asyncio
import json
import logging
import os
import time

import httpx
from dotenv import load_dotenv
from langfuse import get_client, observe

from app import config
from app.llm.resilience import (
    CircuitBreaker,
    CircuitOpenError,
    compute_backoff_with_jitter,
    estimate_tokens_from_messages,
    estimate_tokens_from_text,
    is_retryable_http_error,
)

load_dotenv()  # 读取 agent-runtime/.env(密钥不入库)

logger = logging.getLogger("llm")

API_URL = "https://api.deepseek.com/chat/completions"
DEFAULT_MODEL = "deepseek-chat"


class DeepSeekClient:
    def __init__(
        self,
        api_key: str | None = None,
        model: str = DEFAULT_MODEL,
        timeout: float = 30.0,
        max_retries: int = 2,
        breaker: CircuitBreaker | None = None,
        max_concurrency: int | None = None,
    ):
        self.api_key = api_key or os.environ["DEEPSEEK_API_KEY"]
        self.model = model
        self.timeout = timeout
        self.max_retries = max_retries  # 首次失败之外的额外重试次数
        self.breaker = breaker or CircuitBreaker(
            failure_threshold=config.LLM_CB_FAILURE_THRESHOLD,
            recovery_timeout_s=config.LLM_CB_RECOVERY_SECONDS,
        )
        self._max_concurrency = max(1, max_concurrency or config.LLM_MAX_CONCURRENCY)
        self._semaphore: asyncio.Semaphore | None = None

    def _get_semaphore(self) -> asyncio.Semaphore:
        if self._semaphore is None:
            self._semaphore = asyncio.Semaphore(self._max_concurrency)
        return self._semaphore

    @observe(name="deepseek-chat", as_type="generation")   # generation 类型才会参与成本核算
    async def chat(self, messages: list[dict], tools: list[dict] | None = None) -> dict:
        if not self.breaker.allow_request():
            raise CircuitOpenError("上游大模型熔断已打开，快速降级拒绝请求")

        payload = {"model": self.model, "messages": messages}
        if tools:
            payload["tools"] = tools
        headers = {"Authorization": f"Bearer {self.api_key}"}
        last_err: Exception | None = None

        async with self._get_semaphore():
            for attempt in range(1, self.max_retries + 2):
                start = time.perf_counter()
                try:
                    async with httpx.AsyncClient(timeout=self.timeout) as client:
                        resp = await client.post(API_URL, headers=headers, json=payload)
                        resp.raise_for_status()
                    data = resp.json()
                    elapsed = time.perf_counter() - start
                    self.breaker.record_success()
                    logger.info("llm ok: attempt=%s elapsed=%.2fs usage=%s",
                                attempt, elapsed, data.get("usage", {}))
                    usage = data.get("usage", {})
                    # 把模型名和 token 用量挂到当前 generation 上,Langfuse 才能算钱
                    get_client().update_current_generation(
                        model=self.model,
                        usage_details={"input": usage.get("prompt_tokens", 0),
                                       "output": usage.get("completion_tokens", 0),
                                       "total": usage.get("total_tokens", 0)},
                    )
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
                            "usage": usage}
                except (httpx.HTTPError, KeyError, IndexError) as exc:
                    last_err = exc
                    retryable = is_retryable_http_error(exc)
                    if not retryable or attempt > self.max_retries:
                        if retryable:
                            self.breaker.record_failure()
                        logger.warning("llm failed (no retry): attempt=%s retryable=%s err=%r",
                                       attempt, retryable, exc)
                        break
                    backoff = compute_backoff_with_jitter(attempt)
                    logger.warning("llm attempt %s failed: %s -> jitter backoff %.2fs",
                                   attempt, exc, backoff)
                    await asyncio.sleep(backoff)

        raise RuntimeError(f"大模型调用失败(已尝试 {attempt} 次): {last_err}")

    async def chat_stream(self, messages: list[dict], tools: list[dict] | None = None):
        """流式对话:逐块产出事件。
        {"type": "delta", "content"}               回答文本增量
        {"type": "tool_calls", "tool_calls": [...]} 本轮聚合完成的全部工具调用
        {"type": "done", "usage"}                  结束
        流中断保护：try/finally 在中途异常或客户端断开未收到官方 usage 时，
        用本地估算补报已消耗 token 到 Langfuse 并标记 usage_estimated=True。
        """
        if not self.breaker.allow_request():
            raise CircuitOpenError("上游大模型熔断已打开，快速降级拒绝流式请求")

        payload = {"model": self.model, "messages": messages, "stream": True,
                   "stream_options": {"include_usage": True}}
        if tools:
            payload["tools"] = tools
        headers = {"Authorization": f"Bearer {self.api_key}"}
        content_parts: list[str] = []
        tc_acc: dict[int, dict] = {}          # 工具调用增量按 index 聚合
        usage: dict = {}
        start = time.perf_counter()
        completed_normally = False

        with get_client().start_as_current_observation(
            name="deepseek-chat-stream", as_type="generation"
        ) as generation:
            try:
                async with self._get_semaphore():
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
                completed_normally = True
                self.breaker.record_success()
            except httpx.HTTPError as exc:
                if is_retryable_http_error(exc):
                    self.breaker.record_failure()
                raise RuntimeError(f"大模型流式调用中断: {exc}") from exc
            finally:
                if usage:
                    generation.update(
                        model=self.model,
                        usage_details={
                            "input": usage.get("prompt_tokens", 0),
                            "output": usage.get("completion_tokens", 0),
                            "total": usage.get("total_tokens", 0),
                        },
                        metadata={"stream_aborted": not completed_normally, "usage_estimated": False},
                    )
                else:
                    # 流中途断开或未收到官方 usage 时，用本地估算补齐成本账单
                    est_in = estimate_tokens_from_messages(messages, tools)
                    partial_out = "".join(content_parts) + json.dumps(tc_acc, ensure_ascii=False)
                    est_out = estimate_tokens_from_text(partial_out) if (content_parts or tc_acc) else 0
                    generation.update(
                        model=self.model,
                        usage_details={
                            "input": est_in,
                            "output": est_out,
                            "total": est_in + est_out,
                        },
                        metadata={"stream_aborted": not completed_normally, "usage_estimated": True},
                    )

        logger.info("llm stream done: elapsed=%.2fs usage=%s",
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
