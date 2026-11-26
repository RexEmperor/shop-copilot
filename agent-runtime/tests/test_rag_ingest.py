"""Phase 4 S2 切分、向量化与幂等增量入库单元测试（无外部 PG/Ollama 依赖）。"""
from pathlib import Path

import httpx
import pytest

from app.rag.chunker import chunk_markdown, compute_content_hash, extract_title
from app.rag.embedder import OllamaEmbedder
from app.rag.ingest import ingest_policies


def test_chunk_markdown_preserves_heading_hierarchy_and_splits_long_sections():
    md = (
        "# 七天无理由退货规则\n\n"
        "## 适用条件\n"
        "签收次日起 7 个自然日内可申请退货。\n\n"
        "## 超长条款\n"
        + "这是一条很长的售后说明条款，用于验证超长段落按句切分并保留上下文重叠。 " * 12
    )
    assert extract_title(md) == "七天无理由退货规则"
    chunks = chunk_markdown(md, max_chars=180, overlap_chars=30)
    assert len(chunks) >= 3
    assert chunks[0].heading_path == "七天无理由退货规则 > 适用条件"
    assert "[七天无理由退货规则 > 适用条件]" in chunks[0].content
    assert all(c.chunk_index == i for i, c in enumerate(chunks))
    assert all(len(c.content) <= 185 for c in chunks)


def test_compute_content_hash_normalizes_line_endings():
    assert compute_content_hash("标题\r\n正文\r\n") == compute_content_hash("  标题\n正文  ")


async def test_ollama_embedder_batches_and_validates_dim(monkeypatch):
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        import json

        body = json.loads(request.content.decode("utf-8"))
        calls.append(body["input"])
        return httpx.Response(
            200,
            json={"model": "bge-m3", "embeddings": [[0.1, 0.2, 0.3, 0.4] for _ in body["input"]]},
        )

    transport = httpx.MockTransport(handler)
    orig_async_client = httpx.AsyncClient

    class PatchedClient(orig_async_client):
        def __init__(self, *args, **kwargs):
            kwargs["transport"] = transport
            super().__init__(*args, **kwargs)

    monkeypatch.setattr("app.rag.embedder.httpx.AsyncClient", PatchedClient)

    embedder = OllamaEmbedder(base_url="http://fake-ollama:11434", expected_dim=4)
    vecs = await embedder.embed_texts(["a", "b", "c"], batch_size=2)
    assert len(vecs) == 3
    assert calls == [["a", "b"], ["c"]]

    bad_embedder = OllamaEmbedder(base_url="http://fake-ollama:11434", expected_dim=1024)
    with pytest.raises(RuntimeError, match="维度不匹配"):
        await bad_embedder.embed_query("a")


async def test_ingest_policies_is_idempotent_and_handles_update_and_delete(tmp_path: Path):
    f1 = tmp_path / "01_a.md"
    f2 = tmp_path / "02_b.md"
    f1.write_text("# 规则A\n\n## 第一条\n内容一", encoding="utf-8")
    f2.write_text("# 规则B\n\n## 第一条\n内容二", encoding="utf-8")

    class FakeStore:
        def __init__(self):
            self.docs = {}

        async def get_document_hash(self, doc_key: str):
            doc = self.docs.get(doc_key)
            return doc["hash"] if doc else None

        async def upsert_document_with_chunks(self, **kwargs):
            key = kwargs["doc_key"]
            existing = self.docs.get(key)
            if existing and existing["hash"] == kwargs["content_hash"]:
                return "skipped"
            action = "updated" if existing else "inserted"
            self.docs[key] = {"hash": kwargs["content_hash"], "chunks": kwargs["chunks"]}
            return action

        async def delete_stale_documents(self, source_type: str, active_doc_keys: set[str]):
            stale = [k for k in self.docs if k.startswith(f"{source_type}:") and k not in active_doc_keys]
            for k in stale:
                del self.docs[k]
            return len(stale)

    class FakeEmbedder:
        def __init__(self):
            self.embed_calls = 0

        async def embed_texts(self, texts: list[str]):
            self.embed_calls += len(texts)
            return [[0.5] * 1024 for _ in texts]

    store = FakeStore()
    embedder = FakeEmbedder()

    # 第 1 次：全量插入 2 篇
    r1 = await ingest_policies(store, embedder, policies_dir=tmp_path)
    assert r1 == {"inserted": 2, "updated": 0, "skipped": 0, "deleted": 0}
    first_calls = embedder.embed_calls
    assert first_calls == 2

    # 第 2 次：未改动，全部跳过且 0 次向量化调用
    r2 = await ingest_policies(store, embedder, policies_dir=tmp_path)
    assert r2 == {"inserted": 0, "updated": 0, "skipped": 2, "deleted": 0}
    assert embedder.embed_calls == first_calls

    # 第 3 次：改 f1 + 删 f2，仅重算 f1 并清理 f2
    f1.write_text("# 规则A\n\n## 第一条\n内容一（已修订）", encoding="utf-8")
    f2.unlink()
    r3 = await ingest_policies(store, embedder, policies_dir=tmp_path)
    assert r3 == {"inserted": 0, "updated": 1, "skipped": 0, "deleted": 1}
    assert list(store.docs.keys()) == ["policy:01_a"]
