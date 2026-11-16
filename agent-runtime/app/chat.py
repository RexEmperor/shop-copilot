"""会话服务:多轮上下文管理(Redis 热存储版)。

数据模型:Redis list,key = chat:{session_id},每项一条 JSON 消息。
TODO(S2):MySQL 作为长期存储(审计/防 Redis 数据丢失),Redis 只做热缓存。
TODO(S2):SYSTEM_PROMPT 移入版本化 Prompt 目录,不硬编码。
"""
import json

from redis.asyncio import Redis

from app import config

redis = Redis.from_url(config.REDIS_URL, decode_responses=True)

SYSTEM_PROMPT = "你是智购助手,一个电商导购 AI。用简洁、友好的中文回答。"


def _key(session_id: str) -> str:
    return f"chat:{session_id}"


async def get_history(session_id: str) -> list[dict]:
    raw = await redis.lrange(_key(session_id), 0, -1)
    return [json.loads(item) for item in raw]


async def append_message(session_id: str, role: str, content: str) -> None:
    await redis.rpush(
        _key(session_id),
        json.dumps({"role": role, "content": content}, ensure_ascii=False),
    )
    await redis.expire(_key(session_id), config.HISTORY_TTL_DAYS * 86400)


async def build_messages(session_id: str) -> list[dict]:
    """system prompt + 截断后的历史。

    截断的原因:历史无限增长 → token 费用线性上涨,且终会撑爆模型窗口。
    简单取最近 N 条;Phase 3 升级为"摘要 + 近期原文"的组合策略。
    """
    history = await get_history(session_id)
    trimmed = history[-config.HISTORY_MAX_MESSAGES:]
    return [{"role": "system", "content": SYSTEM_PROMPT}, *trimmed]