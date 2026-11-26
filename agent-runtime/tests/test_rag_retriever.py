"""Phase 4 S3 混合检索单元测试（RRF 融合、阈值过滤与可选 Rerank，无外部依赖）。"""
import pytest

from app.rag.retriever import HybridRetriever, tokenize_zh


def test_tokenize_zh_splits_chinese_and_drops_punctuation():
    tokens = tokenize_zh("七天无理由退货规则！100Wh 充电宝？")
    assert "退货" in tokens
    assert "100wh" in tokens
    assert "！" not in tokens and "？" not in tokens


async def test_hybrid_retriever_rrf_fuses_vector_and_bm25_and_filters_noise():
    chunks = [
        {
            "chunk_id": 1,
            "chunk_index": 0,
            "heading_path": "延迟发货赔付规则",
            "content": "商家超过48小时未发货，按订单实付金额的5%赔付，最低5元最高50元。",
            "metadata": {},
            "doc_key": "policy:03_shipping_delay_compensation",
            "source_type": "policy",
            "title": "发货时效与延迟发货赔付规则",
            "category": "平台规则",
            "source_path": "kb/policies/03_shipping_delay_compensation.md",
        },
        {
            "chunk_id": 2,
            "chunk_index": 0,
            "heading_path": "七天无理由退货",
            "content": "签收次日起7个自然日内支持无理由退货。",
            "metadata": {},
            "doc_key": "policy:01_return_7days",
            "source_type": "policy",
            "title": "七天无理由退货规则",
            "category": "平台规则",
            "source_path": "kb/policies/01_return_7days.md",
        },
        {
            "chunk_id": 3,
            "chunk_index": 0,
            "heading_path": "会员积分",
            "content": "金卡会员享1.5倍积分加速。",
            "metadata": {},
            "doc_key": "policy:16_member_points_vip",
            "source_type": "policy",
            "title": "会员等级与积分规则",
            "category": "平台规则",
            "source_path": "kb/policies/16_member_points_vip.md",
        },
    ]

    class FakeStore:
        async def list_chunks(self, *, source_type=None):
            return chunks

        async def vector_search(self, query_vector, *, top_k=8, source_type=None):
            # 刻意把 chunk 2 排第 1（模拟向量略微偏高），chunk 1 排第 2；BM25 会把 chunk 1 拉回第 1
            return [
                {**chunks[1], "vector_score": 0.62},
                {**chunks[0], "vector_score": 0.60},
                {**chunks[2], "vector_score": 0.25},  # 低于阈值噪声
            ]

    class FakeEmbedder:
        async def embed_query(self, query: str):
            return [0.1] * 1024

    retriever = HybridRetriever(
        store=FakeStore(),
        embedder=FakeEmbedder(),
        reranker=None,
        min_vector_score=0.38,
    )

    hits = await retriever.retrieve("延迟发货超过48小时赔付百分之几", top_k=3)
    assert [h.doc_key for h in hits] == [
        "policy:03_shipping_delay_compensation",
        "policy:01_return_7days",
    ]
    assert hits[0].sources == ["vector", "bm25"]
    assert hits[0].bm25_score is not None and hits[0].bm25_score > 0
    # chunk 3 vector_score=0.25 已被阈值过滤掉
    assert all(h.chunk_id != 3 for h in hits)

    with pytest.raises(ValueError, match="Unsupported retrieve mode"):
        await retriever.retrieve("test", mode="unknown")
