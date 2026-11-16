"""DeepSeek 统一客户端:超时 / 指数退避重试 / 耗时与 token 记录。

约定:项目中所有 LLM 调用必须经由本 client,
便于统一做成本统计与降级;Phase 1 接入 Langfuse 全链路追踪。
"""
import asyncio
import logging
import os
import time

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

    async def chat(self, messages: list[dict]) -> dict:
        """messages: [{"role": "system"|"user"|"assistant", "content": str}, ...]"""
        payload = {"model": self.model, "messages": messages}
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
                return {
                    "content": data["choices"][0]["message"]["content"],
                    "model": self.model,
                    "elapsed_s": round(elapsed, 2),
                    "usage": data.get("usage", {}),
                }
            except (httpx.HTTPError, KeyError, IndexError) as exc:
                last_err = exc
                backoff = 0.5 * 2 ** (attempt - 1)
                logger.warning("deepseek attempt %s failed: %s → retry in %.1fs",
                               attempt, exc, backoff)
                await asyncio.sleep(backoff)

        raise RuntimeError(f"DeepSeek 调用失败(已重试 {self.max_retries} 次): {last_err}")