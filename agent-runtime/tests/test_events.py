"""Phase 5 S3 RocketMQ 异步事件接收、订单主动播报与单条商品增量向量同步单元测试。"""
from app.events import AsyncEventProcessor


async def test_order_status_event_creates_proactive_notification_and_dedups():
    proc = AsyncEventProcessor(redis_client=None)

    payload = {
        "eventId": "evt-ord-ship-01",
        "orderNo": "SO202611240002",
        "userId": 10001,
        "productId": 2,
        "productName": "智能手表 S2",
        "fromStatus": 1,
        "toStatus": 2,
        "toStatusText": "已发货",
        "totalAmountCents": 89900,
        "occurredAt": "2026-11-28T11:35:00Z",
    }
    r1 = await proc.handle_order_status_event(payload)
    assert r1["accepted"] is True and r1["duplicate"] is False
    assert "SO202611240002" in r1["notification"]["message"]
    assert "已由商家发出" in r1["notification"]["message"]

    # 重复事件幂等跳过
    r2 = await proc.handle_order_status_event(payload)
    assert r2["accepted"] is True and r2["duplicate"] is True

    items = await proc.list_notifications(user_id=10001)
    assert len(items) == 1
    assert items[0]["order_no"] == "SO202611240002"

    cleared = await proc.clear_notifications(user_id=10001)
    assert cleared == 1
    assert await proc.list_notifications(user_id=10001) == []


async def test_product_sync_event_incrementally_upserts_and_deletes_on_off_sale():
    class FakeStore:
        def __init__(self):
            self.docs = {}

        async def get_document_hash(self, doc_key: str):
            d = self.docs.get(doc_key)
            return d["hash"] if d else None

        async def upsert_document_with_chunks(self, **kwargs):
            key = kwargs["doc_key"]
            action = "updated" if key in self.docs else "inserted"
            self.docs[key] = {"hash": kwargs["content_hash"], "title": kwargs["title"]}
            return action

        async def delete_document_by_key(self, doc_key: str):
            return 1 if self.docs.pop(doc_key, None) is not None else 0

    class FakeEmbedder:
        def __init__(self):
            self.calls = 0

        async def embed_texts(self, texts: list[str]):
            self.calls += len(texts)
            return [[0.2] * 1024 for _ in texts]

    class FakeRetriever:
        def __init__(self):
            self.invalidated = 0

        def invalidate_bm25_cache(self):
            self.invalidated += 1

    store = FakeStore()
    embedder = FakeEmbedder()
    retriever = FakeRetriever()
    proc = AsyncEventProcessor(
        redis_client=None,
        vector_store=store,
        embedder=embedder,
        retriever=retriever,
    )

    # 商品修改 -> 增量向量化单条商品并刷新 BM25 缓存
    r1 = await proc.handle_product_sync_event({
        "eventId": "evt-prd-upd-1",
        "productId": 2,
        "action": "UPDATED",
        "name": "智能手表 S2 升级版",
        "category": "数码",
        "description": "血氧心率监测，续航 20 天",
        "priceCents": 89900,
        "stock": 80,
        "status": 1,
    })
    assert r1["action"] == "inserted"
    assert embedder.calls == 1
    assert retriever.invalidated == 1
    assert "product:2" in store.docs

    # 内容未变但 eventId 不同的事件 -> content_hash 命中 skipped，0 次重算向量
    r_same = await proc.handle_product_sync_event({
        "eventId": "evt-prd-upd-2",
        "productId": 2,
        "action": "UPDATED",
        "name": "智能手表 S2 升级版",
        "category": "数码",
        "description": "血氧心率监测，续航 20 天",
        "priceCents": 89900,
        "stock": 80,
        "status": 1,
    })
    assert r_same["action"] == "skipped"
    assert embedder.calls == 1

    # 商品下架 -> 立刻从向量库删除并刷新 BM25 缓存
    r_off = await proc.handle_product_sync_event({
        "eventId": "evt-prd-off-3",
        "productId": 2,
        "action": "OFF_SALE",
        "name": "智能手表 S2 升级版",
        "category": "数码",
        "description": "血氧心率监测，续航 20 天",
        "priceCents": 89900,
        "stock": 0,
        "status": 0,
    })
    assert r_off["action"] == "deleted" and r_off["deleted"] == 1
    assert "product:2" not in store.docs
    assert retriever.invalidated == 2
