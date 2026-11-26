"""本地 Ollama BGE-M3 异步 Embedding 客户端。"""
import httpx
from langfuse import observe

from app import config


class OllamaEmbedder:
    """封装 Ollama `/api/embed`，支持批量向量化与维度校验。"""

    def __init__(
        self,
        base_url: str | None = None,
        model: str | None = None,
        timeout_seconds: float | None = None,
        expected_dim: int = config.RAG_EMBED_DIM,
    ):
        self.base_url = (base_url or config.OLLAMA_BASE_URL).rstrip("/")
        self.model = model or config.OLLAMA_EMBED_MODEL
        self.timeout_seconds = (
            config.OLLAMA_TIMEOUT_SECONDS if timeout_seconds is None else timeout_seconds
        )
        self.expected_dim = expected_dim

    @observe(name="ollama-embed")
    async def embed_texts(self, texts: list[str], *, batch_size: int = 16) -> list[list[float]]:
        """批量生成文本向量，并校验返回维度与数量一致。"""
        if not texts:
            return []

        vectors: list[list[float]] = []
        url = f"{self.base_url}/api/embed"
        async with httpx.AsyncClient(timeout=self.timeout_seconds) as client:
            for start in range(0, len(texts), max(1, batch_size)):
                batch = texts[start : start + max(1, batch_size)]
                resp = await client.post(url, json={"model": self.model, "input": batch})
                resp.raise_for_status()
                data = resp.json()
                embs = data.get("embeddings") or []
                if len(embs) != len(batch):
                    raise RuntimeError(
                        f"Ollama embed 返回数量不匹配: expected={len(batch)}, got={len(embs)}"
                    )
                for vec in embs:
                    if self.expected_dim and len(vec) != self.expected_dim:
                        raise RuntimeError(
                            f"向量维度不匹配: expected={self.expected_dim}, got={len(vec)}"
                        )
                    vectors.append([float(x) for x in vec])
        return vectors

    async def embed_query(self, query: str) -> list[float]:
        """单条查询向量化。"""
        vectors = await self.embed_texts([query], batch_size=1)
        return vectors[0]
