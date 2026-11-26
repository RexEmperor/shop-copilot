"""PostgreSQL + pgvector 知识库存储层（幂等增量更新与级联清理）。"""
from contextlib import asynccontextmanager
import json
from typing import Any

from pgvector import Vector
from pgvector.psycopg import register_vector_async
import psycopg
from psycopg.rows import dict_row

from app import config
from app.rag.chunker import ChunkPiece


class RagVectorStore:
    """管理 `kb_document` 与 `kb_chunk` 的幂等写入与统计查询。"""

    def __init__(self, dsn: str | None = None, connection_factory=None):
        self.dsn = config.RAG_DATABASE_URL if dsn is None else dsn
        self._connection_factory = connection_factory

    @asynccontextmanager
    async def _connect(self):
        if self._connection_factory is not None:
            async with self._connection_factory() as conn:
                yield conn
            return

        if not self.dsn:
            raise RuntimeError("RAG_DATABASE_URL 未配置，无法连接 pgvector 数据库")
        async with await psycopg.AsyncConnection.connect(self.dsn, row_factory=dict_row) as conn:
            await register_vector_async(conn)
            yield conn

    async def get_document_hash(self, doc_key: str) -> str | None:
        """读取已入库文档的 content_hash，用于跳过未变更文档的向量化。"""
        async with self._connect() as conn:
            async with conn.cursor() as cur:
                await cur.execute(
                    "SELECT content_hash FROM kb_document WHERE doc_key = %s",
                    (doc_key,),
                )
                row = await cur.fetchone()
                return row["content_hash"] if row else None

    async def upsert_document_with_chunks(
        self,
        *,
        doc_key: str,
        source_type: str,
        title: str,
        category: str,
        source_path: str,
        content_hash: str,
        chunks: list[ChunkPiece],
        embeddings: list[list[float]],
        metadata: dict[str, Any] | None = None,
    ) -> str:
        """单事务内原子更新文档与分块；若哈希未变化则跳过。返回 'inserted' | 'updated' | 'skipped'。"""
        if len(chunks) != len(embeddings):
            raise ValueError(f"chunks({len(chunks)}) 与 embeddings({len(embeddings)}) 数量不一致")

        meta_json = json.dumps(metadata or {}, ensure_ascii=False)
        async with self._connect() as conn:
            async with conn.transaction():
                async with conn.cursor() as cur:
                    await cur.execute(
                        "SELECT id, content_hash FROM kb_document WHERE doc_key = %s",
                        (doc_key,),
                    )
                    existing = await cur.fetchone()
                    if existing and existing["content_hash"] == content_hash:
                        return "skipped"

                    action = "updated" if existing else "inserted"
                    await cur.execute(
                        """
                        INSERT INTO kb_document (
                            doc_key, source_type, title, category, source_path,
                            content_hash, metadata, chunk_count, updated_at
                        )
                        VALUES (%s, %s, %s, %s, %s, %s, %s::jsonb, %s, NOW())
                        ON CONFLICT (doc_key) DO UPDATE SET
                            source_type = EXCLUDED.source_type,
                            title = EXCLUDED.title,
                            category = EXCLUDED.category,
                            source_path = EXCLUDED.source_path,
                            content_hash = EXCLUDED.content_hash,
                            metadata = EXCLUDED.metadata,
                            chunk_count = EXCLUDED.chunk_count,
                            updated_at = NOW()
                        RETURNING id
                        """,
                        (
                            doc_key,
                            source_type,
                            title,
                            category,
                            source_path,
                            content_hash,
                            meta_json,
                            len(chunks),
                        ),
                    )
                    doc_row = await cur.fetchone()
                    doc_id = doc_row["id"]

                    await cur.execute("DELETE FROM kb_chunk WHERE document_id = %s", (doc_id,))
                    for piece, emb in zip(chunks, embeddings):
                        chunk_meta = json.dumps(
                            {
                                **(metadata or {}),
                                "doc_key": doc_key,
                                "title": title,
                                "source_type": source_type,
                                "category": category,
                            },
                            ensure_ascii=False,
                        )
                        await cur.execute(
                            """
                            INSERT INTO kb_chunk (
                                document_id, chunk_index, heading_path,
                                content, token_estimate, embedding, metadata
                            )
                            VALUES (%s, %s, %s, %s, %s, %s, %s::jsonb)
                            """,
                            (
                                doc_id,
                                piece.chunk_index,
                                piece.heading_path,
                                piece.content,
                                piece.token_estimate,
                                Vector(emb),
                                chunk_meta,
                            ),
                        )
                    return action

    async def delete_stale_documents(self, source_type: str, active_doc_keys: set[str]) -> int:
        """清理已被删除或下架的文档（依赖 ON DELETE CASCADE 同步清理 kb_chunk）。"""
        async with self._connect() as conn:
            async with conn.transaction():
                async with conn.cursor() as cur:
                    if active_doc_keys:
                        await cur.execute(
                            "DELETE FROM kb_document WHERE source_type = %s AND NOT (doc_key = ANY(%s))",
                            (source_type, sorted(active_doc_keys)),
                        )
                    else:
                        await cur.execute(
                            "DELETE FROM kb_document WHERE source_type = %s",
                            (source_type,),
                        )
                    return cur.rowcount or 0

    async def stats(self) -> dict[str, Any]:
        """返回知识库当前文档与分块统计。"""
        async with self._connect() as conn:
            async with conn.cursor() as cur:
                await cur.execute(
                    "SELECT source_type, COUNT(*) AS docs, COALESCE(SUM(chunk_count), 0) AS chunks "
                    "FROM kb_document GROUP BY source_type"
                )
                by_type = {
                    row["source_type"]: {"docs": int(row["docs"]), "chunks": int(row["chunks"])}
                    for row in await cur.fetchall()
                }
                await cur.execute("SELECT COUNT(*) AS cnt FROM kb_chunk")
                total_chunks = int((await cur.fetchone())["cnt"])
                return {
                    "by_type": by_type,
                    "total_docs": sum(v["docs"] for v in by_type.values()),
                    "total_chunks": total_chunks,
                }
