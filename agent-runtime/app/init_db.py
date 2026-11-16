"""一次性脚本:创建 chat_* 表。开发期用 create_all;正式项目用 Alembic 做版本化迁移。"""
import asyncio

from app.db import Base, ChatMessage, ChatSession, engine  # noqa: F401


async def main():
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    await engine.dispose()   # 循环关闭前归还所有池连接,避免 __del__ 期的 Event loop is closed 噪音
    print("tables created")


asyncio.run(main())