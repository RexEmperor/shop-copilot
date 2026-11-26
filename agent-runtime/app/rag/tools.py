"""面向多 Agent 的 RAG 检索工具（平台规则引用溯源 + 语义导购回源校验）。"""
import json
import logging
from typing import Any

from app import config
from app.mcp_client import mcp_client
from app.rag.retriever import HybridRetriever

logger = logging.getLogger("rag.tools")

_retriever = HybridRetriever()


def get_retriever() -> HybridRetriever:
    return _retriever


async def search_knowledge_base_impl(
    query: str,
    top_k: int = 3,
    *,
    retriever: HybridRetriever | None = None,
) -> str:
    """混合检索平台规则知识库，返回带引用元数据的结构化 JSON；无高相关命中时触发防幻觉护栏。"""
    ret = retriever or _retriever
    k = min(max(int(top_k or 3), 1), 5)
    try:
        hits = await ret.retrieve(
            query,
            top_k=k,
            source_type="policy",
            mode="hybrid",
            min_vector_score=config.RAG_POLICY_MIN_VECTOR_SCORE,
            strict_vector_threshold=True,
        )
    except Exception as exc:
        logger.warning("Knowledge base retrieval failed: %r", exc)
        return json.dumps(
            {
                "success": False,
                "code": 500,
                "hit_count": 0,
                "guardrail_triggered": True,
                "citations": [],
                "message": f"知识库检索服务暂时异常（{type(exc).__name__}），请勿编造规则，建议用户稍后重试或转人工。",
            },
            ensure_ascii=False,
        )

    if not hits:
        return json.dumps(
            {
                "success": True,
                "code": 200,
                "hit_count": 0,
                "guardrail_triggered": True,
                "citations": [],
                "message": (
                    "知识库中未检索到与该问题直接相关的平台规则条款。"
                    "请严格遵守防幻觉护栏：严禁编造任何金额、比例或政策细则，"
                    "必须如实告知用户“当前知识库暂未查到该具体条款”，并建议回复“转人工”咨询人工客服。"
                ),
            },
            ensure_ascii=False,
        )

    citations = [
        {
            "index": idx,
            "doc_key": h.doc_key,
            "title": h.title,
            "heading_path": h.heading_path,
            "source_path": h.source_path,
            "vector_score": round(h.vector_score, 4) if h.vector_score is not None else None,
            "bm25_score": round(h.bm25_score, 4) if h.bm25_score is not None else None,
            "sources": list(h.sources),
            "content": h.content,
        }
        for idx, h in enumerate(hits, start=1)
    ]
    return json.dumps(
        {
            "success": True,
            "code": 200,
            "hit_count": len(citations),
            "guardrail_triggered": False,
            "citations": citations,
            "message": (
                f"命中 {len(citations)} 条平台规则。请严格依据上述条款原文作答，"
                "并在回答中以 [来源: 文档标题] 标注引用出处；若条款未覆盖用户问的具体细则，须如实说明未查到。"
            ),
        },
        ensure_ascii=False,
    )


async def semantic_search_products_impl(
    query: str,
    top_k: int = 3,
    *,
    retriever: HybridRetriever | None = None,
    mcp=mcp_client,
) -> str:
    """语义召回商品并回源 Java MCP 实时校验在售状态、价格与库存（RAG 管语义，Java 管权威）。"""
    ret = retriever or _retriever
    k = min(max(int(top_k or 3), 1), 5)
    try:
        hits = await ret.retrieve(
            query,
            top_k=k,
            source_type="product",
            mode="hybrid",
            min_vector_score=config.RAG_MIN_VECTOR_SCORE,
        )
    except Exception as exc:
        logger.warning("Semantic product retrieval failed: %r", exc)
        return json.dumps(
            {
                "success": False,
                "code": 500,
                "records": [],
                "citations": [],
                "message": f"语义选品检索服务暂时异常（{type(exc).__name__}），请改用 search_products 或稍后重试。",
            },
            ensure_ascii=False,
        )

    verified_records: list[dict[str, Any]] = []
    citations: list[dict[str, Any]] = []
    seen_ids: set[int] = set()

    for idx, hit in enumerate(hits, start=1):
        pid = (hit.metadata or {}).get("product_id")
        if pid is None and hit.doc_key.startswith("product:"):
            try:
                pid = int(hit.doc_key.split(":", 1)[1])
            except ValueError:
                pid = None
        if pid is None or pid in seen_ids:
            continue
        seen_ids.add(int(pid))

        # 回源 Java MCP 校验实时在售状态、价格与库存，防止向量库缓存已下架/售罄商品
        raw = await mcp.call_tool("get_product_detail", {"productId": int(pid)})
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            continue
        if not payload.get("success") or not isinstance(payload.get("data"), dict):
            # 例如 404 商品已下架 -> 自动过滤
            continue

        prod = payload["data"]
        if int(prod.get("status", 1)) != 1:
            continue
        verified_records.append(prod)
        citations.append(
            {
                "index": idx,
                "doc_key": hit.doc_key,
                "title": prod.get("name") or hit.title,
                "heading_path": f"在售商品 > {prod.get('category', '')}",
                "source_path": hit.source_path,
                "vector_score": round(hit.vector_score, 4) if hit.vector_score is not None else None,
            }
        )

    return json.dumps(
        {
            "success": True,
            "code": 200,
            "total": len(verified_records),
            "records": verified_records,
            "citations": citations,
            "message": (
                f"语义匹配并经业务库实时核验，共找到 {len(verified_records)} 件在售商品。"
                if verified_records
                else "暂未找到语义匹配的在售商品，可尝试向用户确认预算或品类。"
            ),
        },
        ensure_ascii=False,
    )
