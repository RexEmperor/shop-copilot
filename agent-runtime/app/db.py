"""MySQL 持久层:chat_* 表的 ORM 模型(唯一事实来源)"""
from datetime import datetime

from sqlalchemy import BigInteger, DateTime, ForeignKey, Index, Integer, String, Text, func
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

from app import config

engine = create_async_engine(config.DATABASE_URL, pool_pre_ping=True, pool_size=5)
async_session = async_sessionmaker(engine, expire_on_commit=False)


class Base(DeclarativeBase):
    pass


class ChatSession(Base):
    __tablename__ = "chat_session"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)   # 即 session_id
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())


class ChatMessage(Base):
    __tablename__ = "chat_message"
    __table_args__ = (
        Index("idx_session", "session_id", "id"),   # 复合索引:WHERE session_id + ORDER BY id 一次搞定
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    session_id: Mapped[str] = mapped_column(String(64), ForeignKey("chat_session.id"))
    role: Mapped[str] = mapped_column(String(16))
    content: Mapped[str] = mapped_column(Text)
    model: Mapped[str | None] = mapped_column(String(64))          # assistant 消息记录用的模型
    prompt_tokens: Mapped[int | None] = mapped_column(Integer)
    completion_tokens: Mapped[int | None] = mapped_column(Integer)
    elapsed_ms: Mapped[int | None] = mapped_column(Integer)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())