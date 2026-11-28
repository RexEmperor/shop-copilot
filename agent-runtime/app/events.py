"""Phase 5 S3 异步事件处理器：
1. 接收 Java RocketMQ 推送的订单状态变更事件 -> 幂等去重并生成用户主动播报通知；
2. 接收 Java RocketMQ 推送的商品变更/下架事件 -> 幂等去重并执行单条商品增量向量同步或删除。
"""
import json
import logging
import time
from typing import Any

from app import config
from app.chat import redis as default_redis
from app.rag.chunker import chunk_markdown, compute_content_hash
from app.rag.embedder import OllamaEmbedder
from app.rag.ingest import _format_product_markdown
from app.rag.store import RagVectorStore
from app.rag.tools import get_retriever

logger = logging.getLogger("events")

_STATUS_TEMPLATES = {
    1: "💳 订单动态：您的订单 {order_no}（{product_name}，¥{amount_yuan}）已支付成功，商家将在 48 小时内为您安排发货！",
    2: "📦 订单动态：您的订单 {order_no}（{product_name}）已由商家发出，请注意查收物流信息！",
    3: "✅ 订单动态：您的订单 {order_no}（{product_name}）已确认收货完成。如对商品满意欢迎继续逛逛，7 天内享价保与售后保障！",
    4: "🔔 订单动态：您的订单 {order_no}（{product_name}）已取消，对应库存已释放。",
}


def cents_to_yuan_str(cents: int) -> str:
    return f"{int(cents) / 100:.2f}"


class AsyncEventProcessor:
    """消费来自 RocketMQ 转发器的业务事件（支持 Redis 优先 + 进程内存降级）。"""

    def __init__(
        self,
        *,
        redis_client=None,
        vector_store: RagVectorStore | None = None,
        embedder: OllamaEmbedder | None = None,
        retriever=None,
        dedup_ttl_s: int = 86400,
    ):
        self._redis = redis_client
        self._store = vector_store
        self._embedder = embedder
        self._retriever = retriever
        self.dedup_ttl_s = dedup_ttl_s
        self._mem_dedup: dict[str, float] = {}
        self._mem_notifs: dict[int, list[dict[str, Any]]] = {}

    def _get_store(self) -> RagVectorStore:
        if self._store is None:
            self._store = RagVectorStore()
        return self._store

    def _get_embedder(self) -> OllamaEmbedder:
        if self._embedder is None:
            self._embedder = OllamaEmbedder()
        return self._embedder

    def _get_retriever(self):
        return self._retriever if self._retriever is not None else get_retriever()

    async def _acquire_dedup(self, event_id: str) -> bool:
        if not event_id:
            return True
        key = f"agent:event:dedup:{event_id}"
        if self._redis is not None:
            try:
                ok = await self._redis.set(key, "1", nx=True, ex=self.dedup_ttl_s)
                return bool(ok)
            except Exception as exc:
                logger.warning("Redis event dedup unavailable, fallback to memory: %r", exc)
        now = time.monotonic()
        exp = self._mem_dedup.get(event_id)
        if exp and now <= exp:
            return False
        self._mem_dedup[event_id] = now + self.dedup_ttl_s
        return True

    async def handle_order_status_event(self, payload: dict[str, Any]) -> dict[str, Any]:
        event_id = str(payload.get("eventId") or "")
        if not await self._acquire_dedup(event_id):
            return {"accepted": True, "duplicate": True, "event_id": event_id}

        order_no = str(payload.get("orderNo") or "")
        user_id = int(payload.get("userId") or 10001)
        product_name = str(payload.get("productName") or "商品")
        to_status = int(payload.get("toStatus", -1))
        to_status_text = str(payload.get("toStatusText") or "状态变更")
        amount_yuan = cents_to_yuan_str(int(payload.get("totalAmountCents") or 0))

        template = _STATUS_TEMPLATES.get(
            to_status,
            "🔔 订单动态：您的订单 {order_no}（{product_name}）状态已更新为【{status_text}】。",
        )
        message = template.format(
            order_no=order_no,
            product_name=product_name,
            amount_yuan=amount_yuan,
            status_text=to_status_text,
        )
        notif = {
            "event_id": event_id,
            "user_id": user_id,
            "order_no": order_no,
            "status": to_status,
            "status_text": to_status_text,
            "message": message,
            "occurred_at": payload.get("occurredAt") or "",
            "received_at": time.time(),
        }

        pushed_redis = False
        if self._redis is not None:
            try:
                list_key = f"notif:user:{user_id}"
                await self._redis.lpush(list_key, json.dumps(notif, ensure_ascii=False))
                await self._redis.ltrim(list_key, 0, 49)
                await self._redis.expire(list_key, 7 * 86400)
                pushed_redis = True
            except Exception as exc:
                logger.warning("Redis push notification failed, fallback to memory: %r", exc)

        if not pushed_redis:
            bucket = self._mem_notifs.setdefault(user_id, [])
            bucket.insert(0, notif)
            del bucket[50:]

        logger.info("Order notification queued: user=%s order=%s status=%s", user_id, order_no, to_status_text)
        return {"accepted": True, "duplicate": False, "notification": notif}

    async def list_notifications(self, user_id: int = 10001, limit: int = 10) -> list[dict[str, Any]]:
        k = min(max(int(limit or 10), 1), 50)
        if self._redis is not None:
            try:
                raw_items = await self._redis.lrange(f"notif:user:{user_id}", 0, k - 1)
                return [json.loads(x) for x in raw_items]
            except Exception as exc:
                logger.warning("Redis list notifications failed, fallback to memory: %r", exc)
        return list((self._mem_notifs.get(user_id) or [])[:k])

    async def clear_notifications(self, user_id: int = 10001) -> int:
        cleared = len(self._mem_notifs.pop(user_id, []))
        if self._redis is not None:
            try:
                key = f"notif:user:{user_id}"
                cleared = int(await self._redis.llen(key))
                await self._redis.delete(key)
            except Exception as exc:
                logger.warning("Redis clear notifications failed: %r", exc)
        return cleared

    async def handle_product_sync_event(self, payload: dict[str, Any]) -> dict[str, Any]:
        """响应商品新增/修改/上下架事件：仅针对单件商品增量重建或删除向量。"""
        event_id = str(payload.get("eventId") or "")
        if not await self._acquire_dedup(event_id):
            return {"accepted": True, "duplicate": True, "event_id": event_id}

        product_id = int(payload["productId"])
        doc_key = f"product:{product_id}"
        status = int(payload.get("status", 1))
        store = self._get_store()
        retriever = self._get_retriever()

        if status != 1:
            deleted = await store.delete_document_by_key(doc_key)
            retriever.invalidate_bm25_cache()
            logger.info("Product vector removed on OFF_SALE: doc_key=%s deleted=%s", doc_key, deleted)
            return {"accepted": True, "duplicate": False, "action": "deleted", "doc_key": doc_key, "deleted": deleted}

        price_yuan = cents_to_yuan_str(int(payload.get("priceCents") or 0))
        md_text = _format_product_markdown({
            "id": product_id,
            "name": payload.get("name"),
            "category": payload.get("category"),
            "priceYuan": price_yuan,
            "stock": payload.get("stock"),
            "description": payload.get("description"),
        })
        content_hash = compute_content_hash(md_text)
        existing_hash = await store.get_document_hash(doc_key)
        if existing_hash == content_hash:
            return {"accepted": True, "duplicate": False, "action": "skipped", "doc_key": doc_key}

        chunks = chunk_markdown(
            md_text,
            max_chars=config.RAG_CHUNK_MAX_CHARS,
            overlap_chars=config.RAG_CHUNK_OVERLAP_CHARS,
            fallback_title=str(payload.get("name") or doc_key),
        )
        embeddings = await self._get_embedder().embed_texts([c.content for c in chunks])
        action = await store.upsert_document_with_chunks(
            doc_key=doc_key,
            source_type="product",
            title=str(payload.get("name") or doc_key),
            category=str(payload.get("category") or ""),
            source_path=f"mcp://product/{product_id}",
            content_hash=content_hash,
            chunks=chunks,
            embeddings=embeddings,
            metadata={"product_id": product_id, "price_yuan": price_yuan},
        )
        retriever.invalidate_bm25_cache()
        logger.info("Product vector incrementally synced: doc_key=%s action=%s", doc_key, action)
        return {"accepted": True, "duplicate": False, "action": action, "doc_key": doc_key}


event_processor = AsyncEventProcessor(redis_client=default_redis)
