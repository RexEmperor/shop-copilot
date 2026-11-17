from app.chat import append_message, get_history, build_messages
from app.chat import redis as app_redis


async def test_append_and_roundtrip(session_id):
    await append_message(session_id, "user", "你好")
    await append_message(session_id, "assistant", "你好呀")
    history = await get_history(session_id)
    assert [m["role"] for m in history] == ["user", "assistant"]


async def test_cache_miss_refills_from_mysql(session_id):
    """S2 的灵魂测试:删缓存后必须能从 MySQL 回源并回填"""
    await append_message(session_id, "user", "我叫郭克")            # 双写
    await app_redis.delete(f"chat:{session_id}")                    # 模拟缓存丢失
    history = await get_history(session_id)                         # 走回源链路
    assert history[0]["content"] == "我叫郭克"                      # 数据回来了
    assert await app_redis.llen(f"chat:{session_id}") == 1          # 且已回填


async def test_build_messages_truncates(session_id, monkeypatch):
    from app import config
    monkeypatch.setattr(config, "HISTORY_MAX_MESSAGES", 2)          # 临时改小截断阈值
    for i in range(5):
        await append_message(session_id, "user", f"msg{i}")

    messages = await build_messages(session_id)
    assert len(messages) == 3                                       # system + 最近2条
    assert messages[0]["role"] == "system"
    assert messages[-1]["content"] == "msg4"                        # 留的是最新的