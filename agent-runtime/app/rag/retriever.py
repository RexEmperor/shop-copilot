"""Phase 4 混合检索器：pgvector 稠密向量 + jieba BM25 稀疏关键词 + RRF 倒数排名融合 + 可插拔 Rerank。"""
import asyncio
from dataclasses import dataclass, field
import logging
import re
import time
from typing import Any, Protocol

import httpx
import jieba
from langfuse import observe
from rank_bm25 import BM25Okapi

from app import config
from app.rag.embedder import OllamaEmbedder
from app.rag.store import RagVectorStore

logger = logging.getLogger("rag.retriever")

_TOKEN_SKIP_RE = re.compile(r"^[\W_]+$", re.UNICODE)


def tokenize_zh(text: str) -> list[str]:
    """中文搜索引擎分词（去除纯标点空白，英文统一小写）。"""
    tokens: list[str] = []
    for tok in jieba.lcut_for_search(text):
        cleaned = tok.strip().lower()
        if not cleaned or _TOKEN_SKIP_RE.match(cleaned):
            continue
        tokens.append(cleaned)
    return tokens


@dataclass
class RagHit:
    chunk_id: int
    doc_key: str
    source_type: str
    title: str
    category: str
    heading_path: str
    content: str
    metadata: dict[str, Any] = field(default_factory=dict)
    source_path: str = ""
    vector_score: float | None = None
    bm25_score: float | None = None
    rrf_score: float = 0.0
    rerank_score: float | None = None
    final_score: float = 0.0
    sources: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "chunk_id": self.chunk_id,
            "doc_key": self.doc_key,
            "source_type": self.source_type,
            "title": self.title,
            "category": self.category,
            "heading_path": self.heading_path,
            "content": self.content,
            "metadata": self.metadata,
            "source_path": self.source_path,
            "vector_score": round(self.vector_score, 4) if self.vector_score is not None else None,
            "bm25_score": round(self.bm25_score, 4) if self.bm25_score is not None else None,
            "rrf_score": round(self.rrf_score, 6),
            "rerank_score": round(self.rerank_score, 4) if self.rerank_score is not None else None,
            "final_score": round(self.final_score, 6),
            "sources": list(self.sources),
        }


class Reranker(Protocol):
    async def rerank(self, query: str, hits: list[RagHit], top_k: int) -> list[RagHit]: ...


class SiliconFlowReranker:
    """可选在线精排器（配置 SILICONFLOW_API_KEY 后自动启用 bge-reranker-v2-m3）。"""

    def __init__(
        self,
        api_key: str | None = None,
        model: str | None = None,
        timeout_seconds: float = 10.0,
    ):
        self.api_key = config.SILICONFLOW_API_KEY if api_key is None else api_key
        self.model = model or config.SILICONFLOW_RERANK_MODEL
        self.timeout_seconds = timeout_seconds

    async def rerank(self, query: str, hits: list[RagHit], top_k: int) -> list[RagHit]:
        if not self.api_key or not hits:
            return hits[:top_k]
        payload = {
            "model": self.model,
            "query": query,
            "documents": [h.content for h in hits],
            "top_n": min(top_k, len(hits)),
        }
        headers = {"Authorization": f"Bearer {self.api_key}"}
        try:
            async with httpx.AsyncClient(timeout=self.timeout_seconds) as client:
                resp = await client.post(
                    "https://api.siliconflow.cn/v1/rerank",
                    headers=headers,
                    json=payload,
                )
                resp.raise_for_status()
            results = resp.json().get("results") or []
            reranked: list[RagHit] = []
            for item in results:
                idx = int(item["index"])
                hit = hits[idx]
                hit.rerank_score = float(item.get("relevance_score", 0.0))
                hit.final_score = hit.rerank_score
                reranked.append(hit)
            return reranked[:top_k]
        except Exception as exc:
            logger.warning("Rerank failed, falling back to RRF order: %r", exc)
            return hits[:top_k]


class HybridRetriever:
    """双路混合检索（pgvector 余弦向量 + jieba BM25）与 RRF 融合。"""

    def __init__(
        self,
        store: RagVectorStore | None = None,
        embedder: OllamaEmbedder | None = None,
        reranker: Reranker | None = None,
        *,
        bm25_ttl_seconds: float = 120.0,
        rrf_k: int | None = None,
        min_vector_score: float | None = None,
    ):
        self.store = store or RagVectorStore()
        self.embedder = embedder or OllamaEmbedder()
        if reranker is not None:
            self.reranker: Reranker | None = reranker
        elif config.SILICONFLOW_API_KEY:
            self.reranker = SiliconFlowReranker()
        else:
            self.reranker = None

        self.bm25_ttl_seconds = bm25_ttl_seconds
        self.rrf_k = config.RAG_RRF_K if rrf_k is None else rrf_k
        self.min_vector_score = (
            config.RAG_MIN_VECTOR_SCORE if min_vector_score is None else min_vector_score
        )
        self._bm25_cache: dict[str, tuple[float, list[dict[str, Any]], BM25Okapi | None]] = {}
        self._bm25_lock = asyncio.Lock()

    def invalidate_bm25_cache(self) -> None:
        self._bm25_cache.clear()

    async def _get_bm25_index(
        self, source_type: str | None
    ) -> tuple[list[dict[str, Any]], BM25Okapi | None]:
        key = source_type or "__ALL__"
        now = time.monotonic()
        cached = self._bm25_cache.get(key)
        if cached and (now - cached[0]) < self.bm25_ttl_seconds:
            return cached[1], cached[2]

        async with self._bm25_lock:
            cached = self._bm25_cache.get(key)
            if cached and (time.monotonic() - cached[0]) < self.bm25_ttl_seconds:
                return cached[1], cached[2]

            chunks = await self.store.list_chunks(source_type=source_type)
            if not chunks:
                self._bm25_cache[key] = (time.monotonic(), [], None)
                return [], None

            tokenized = [tokenize_zh(c["content"]) or [c["content"]] for c in chunks]
            index = BM25Okapi(tokenized)
            self._bm25_cache[key] = (time.monotonic(), chunks, index)
            return chunks, index

    async def _bm25_search(
        self, query: str, *, top_k: int, source_type: str | None
    ) -> list[dict[str, Any]]:
        chunks, index = await self._get_bm25_index(source_type)
        if not chunks or index is None:
            return []
        q_tokens = tokenize_zh(query)
        if not q_tokens:
            return []
        scores = index.get_scores(q_tokens)
        ranked = sorted(
            ((idx, float(score)) for idx, score in enumerate(scores) if score > 0.0),
            key=lambda x: x[1],
            reverse=True,
        )[: max(1, top_k)]
        return [{**chunks[idx], "bm25_score": score} for idx, score in ranked]

    @observe(name="rag-hybrid-retrieve")
    async def retrieve(
        self,
        query: str,
        *,
        top_k: int | None = None,
        candidate_k: int | None = None,
        source_type: str | None = None,
        mode: str = "hybrid",
        min_vector_score: float | None = None,
    ) -> list[RagHit]:
        """执行向量、BM25 或 RRF 混合检索。

        mode:
            - "hybrid": 向量 + BM25 并行召回，RRF 融合排序
            - "vector": 仅向量召回（用于消融实验）
            - "bm25":   仅 BM25 关键词召回（用于消融实验）
        """
        if not query or not query.strip():
            return []

        k = config.RAG_TOP_K if top_k is None else top_k
        cand_k = config.RAG_CANDIDATE_K if candidate_k is None else max(k, candidate_k)
        threshold = self.min_vector_score if min_vector_score is None else min_vector_score

        vec_rows: list[dict[str, Any]] = []
        bm25_rows: list[dict[str, Any]] = []

        if mode == "vector":
            q_vec = await self.embedder.embed_query(query)
            vec_rows = await self.store.vector_search(
                q_vec, top_k=cand_k, source_type=source_type
            )
        elif mode == "bm25":
            bm25_rows = await self._bm25_search(query, top_k=cand_k, source_type=source_type)
        elif mode == "hybrid":
            async def _run_vec():
                q_vec = await self.embedder.embed_query(query)
                return await self.store.vector_search(
                    q_vec, top_k=cand_k, source_type=source_type
                )

            vec_rows, bm25_rows = await asyncio.gather(
                _run_vec(),
                self._bm25_search(query, top_k=cand_k, source_type=source_type),
            )
        else:
            raise ValueError(f"Unsupported retrieve mode: {mode!r}")

        hits_by_id: dict[int, RagHit] = {}

        def _ensure_hit(row: dict[str, Any]) -> RagHit:
            cid = int(row["chunk_id"])
            if cid not in hits_by_id:
                hits_by_id[cid] = RagHit(
                    chunk_id=cid,
                    doc_key=row["doc_key"],
                    source_type=row["source_type"],
                    title=row["title"],
                    category=row["category"],
                    heading_path=row["heading_path"],
                    content=row["content"],
                    metadata=row.get("metadata") or {},
                    source_path=row.get("source_path") or "",
                )
            return hits_by_id[cid]

        for rank, row in enumerate(vec_rows, start=1):
            hit = _ensure_hit(row)
            hit.vector_score = float(row["vector_score"])
            if "vector" not in hit.sources:
                hit.sources.append("vector")
            hit.rrf_score += 1.0 / (self.rrf_k + rank)

        for rank, row in enumerate(bm25_rows, start=1):
            hit = _ensure_hit(row)
            hit.bm25_score = float(row["bm25_score"])
            if "bm25" not in hit.sources:
                hit.sources.append("bm25")
            hit.rrf_score += 1.0 / (self.rrf_k + rank)

        candidates = list(hits_by_id.values())
        if mode == "vector":
            candidates = [
                h for h in candidates if (h.vector_score or 0.0) >= threshold
            ]
            candidates.sort(key=lambda h: h.vector_score or 0.0, reverse=True)
            for h in candidates:
                h.final_score = h.vector_score or 0.0
        elif mode == "bm25":
            candidates.sort(key=lambda h: h.bm25_score or 0.0, reverse=True)
            for h in candidates:
                h.final_score = h.bm25_score or 0.0
        else:
            # 混合模式过滤纯低相关噪声：若既不在高语义区又无强关键词命中，过滤掉防误召回
            filtered: list[RagHit] = []
            for h in candidates:
                v_score = h.vector_score
                if v_score is not None and v_score < threshold and h.bm25_score is None:
                    continue
                if v_score is not None and v_score < (threshold * 0.78):
                    continue
                h.final_score = h.rrf_score
                filtered.append(h)
            candidates = filtered
            candidates.sort(key=lambda h: h.rrf_score, reverse=True)

        if self.reranker is not None and mode == "hybrid" and candidates:
            return await self.reranker.rerank(query, candidates[:cand_k], k)
        return candidates[:k]
