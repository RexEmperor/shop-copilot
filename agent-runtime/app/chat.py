"""会话服务:MySQL事实源 + Redis热缓存。

写入:双写(MySQL先行,失败则中止——不出现"缓存有、库里没有"的幽灵消息)
读取:Cache-aside——先Redis,未命中回源MySQL并回填。
"""
import json

from redis.asyncio import Redis
from sqlalchemy import select

from app import config
from app.db import ChatMessage, ChatSession, async_session
from app.prompts import load_prompt

redis = Redis.from_url(config.REDIS_URL, decode_responses=True)

SYSTEM_PROMPT = load_prompt("shopping_assistant", config.PROMPT_VERSION)


def _key(session_id: str) -> str:
    return f"chat:{session_id}"


async def get_history(session_id: str) -> list[dict]:
    raw = await redis.lrange(_key(session_id), 0, -1)
    if raw:
        return [json.loads(item) for item in raw]

    # 缓存未命中 → 回源 MySQL → 回填 Redis
    async with async_session() as db:
        rows = (await db.execute(
            select(ChatMessage)
            .where(ChatMessage.session_id == session_id)
            .order_by(ChatMessage.id)
        )).scalars().all()
    history = [{"role": r.role, "content": r.content} for r in rows]
    if history:
        pipe = redis.pipeline()
        for m in history:
            pipe.rpush(_key(session_id), json.dumps(m, ensure_ascii=False))
        pipe.expire(_key(session_id), config.HISTORY_TTL_DAYS * 86400)
        await pipe.execute()
    return history


async def append_message(session_id: str, role: str, content: str, *,
                         model: str | None = None,
                         usage: dict | None = None,
                         elapsed_ms: int | None = None) -> None:
    async with async_session() as db:
        if await db.get(ChatSession, session_id) is None:
            db.add(ChatSession(id=session_id))
            # 关键:两个模型间没有 relationship(),SQLAlchemy 无法推断父子表的
            # INSERT 先后顺序。必须显式 flush 先落父表,否则 chat_message 的
            # INSERT 可能先执行 → 外键 1452(本次事故根因)
            await db.flush()
        db.add(ChatMessage(
            session_id=session_id, role=role, content=content, model=model,
            prompt_tokens=(usage or {}).get("prompt_tokens"),
            completion_tokens=(usage or {}).get("completion_tokens"),
            elapsed_ms=elapsed_ms,
        ))
        await db.commit()   # MySQL 落库成功才写缓存

    await redis.rpush(_key(session_id),
                      json.dumps({"role": role, "content": content}, ensure_ascii=False))
    await redis.expire(_key(session_id), config.HISTORY_TTL_DAYS * 86400)


async def build_messages(session_id: str) -> list[dict]:
    history = await get_history(session_id)
    trimmed = history[-config.HISTORY_MAX_MESSAGES:]
    return [{"role": "system", "content": SYSTEM_PROMPT}, *trimmed]