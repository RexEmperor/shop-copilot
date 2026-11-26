"""Phase 4 S4 RAG 工具（知识库引用溯源、防幻觉护栏、语义导购回源校验）单元测试。"""
import json

from app.rag.retriever import RagHit
from app.rag.tools import search_knowledge_base_impl, semantic_search_products_impl


async def test_search_knowledge_base_returns_citations_or_triggers_guardrail():
    class FakeRetriever:
        def __init__(self, hits):
            self._hits = hits

        async def retrieve(self, query, **kwargs):
            return self._hits

    hit = RagHit(
        chunk_id=10,
        doc_key="policy:08_extended_warranty",
        source_type="policy",
        title="店铺延长保修服务说明",
        category="平台规则",
        heading_path="店铺延长保修服务说明 > 延保适用范围与收费标准",
        content="1 年延长保修收费 69 元，不包含意外进水。",
        source_path="kb/policies/08_extended_warranty.md",
        vector_score=0.76,
        bm25_score=6.2,
        sources=["vector", "bm25"],
    )

    ok_raw = await search_knowledge_base_impl("延保多少钱", retriever=FakeRetriever([hit]))
    ok = json.loads(ok_raw)
    assert ok["success"] is True
    assert ok["hit_count"] == 1
    assert ok["guardrail_triggered"] is False
    assert ok["citations"][0]["title"] == "店铺延长保修服务说明"

    miss_raw = await search_knowledge_base_impl("跨境关税多少", retriever=FakeRetriever([]))
    miss = json.loads(miss_raw)
    assert miss["success"] is True
    assert miss["hit_count"] == 0
    assert miss["guardrail_triggered"] is True
    assert "暂未查到" in miss["message"]


async def test_semantic_search_products_verifies_live_status_and_filters_off_sale():
    hits = [
        RagHit(
            chunk_id=1,
            doc_key="product:2",
            source_type="product",
            title="智能手表 S2",
            category="数码",
            heading_path="智能手表 S2",
            content="血氧监测、全天候心率记录，续航14天",
            metadata={"product_id": 2},
            vector_score=0.61,
        ),
        RagHit(
            chunk_id=2,
            doc_key="product:10",
            source_type="product",
            title="复古台灯",
            category="家居",
            heading_path="复古台灯",
            content="可调光暖光台灯",
            metadata={"product_id": 10},
            vector_score=0.58,
        ),
    ]

    class FakeRetriever:
        async def retrieve(self, query, **kwargs):
            return hits

    class FakeMcp:
        async def call_tool(self, name, args):
            assert name == "get_product_detail"
            if args["productId"] == 2:
                return json.dumps(
                    {
                        "success": True,
                        "code": 200,
                        "data": {
                            "id": 2,
                            "name": "智能手表 S2",
                            "category": "数码",
                            "priceYuan": "899.00",
                            "stock": 80,
                            "status": 1,
                        },
                    },
                    ensure_ascii=False,
                )
            return json.dumps(
                {"success": False, "code": 404, "message": "商品已下架"},
                ensure_ascii=False,
            )

    raw = await semantic_search_products_impl(
        "送长辈的健康礼物", retriever=FakeRetriever(), mcp=FakeMcp()
    )
    res = json.loads(raw)
    assert res["success"] is True
    assert res["total"] == 1
    assert res["records"][0]["id"] == 2
    assert res["records"][0]["priceYuan"] == "899.00"
    assert len(res["citations"]) == 1
    assert res["citations"][0]["doc_key"] == "product:2"
