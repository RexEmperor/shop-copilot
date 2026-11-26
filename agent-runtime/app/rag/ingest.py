"""Phase 4 知识库增量入库脚本（平台规则文档 + Java MCP 在售商品目录）。

用法：
    cd agent-runtime && uv run python -m app.rag.ingest
    cd agent-runtime && uv run python -m app.rag.ingest --policies-only
    cd agent-runtime && uv run python -m app.rag.ingest --products-only
"""
import argparse
import asyncio
import json
from pathlib import Path
from typing import Any

from app import config
from app.mcp_client import mcp_client
from app.rag.chunker import chunk_markdown, compute_content_hash, extract_title
from app.rag.embedder import OllamaEmbedder
from app.rag.store import RagVectorStore

DEFAULT_POLICIES_DIR = Path(__file__).resolve().parents[2] / "kb" / "policies"


def _format_product_markdown(item: dict[str, Any]) -> str:
    """将 Java MCP 返回的商品结构化数据渲染为适合语义检索的 Markdown 文档。"""
    name = item.get("name") or f"商品#{item.get('id')}"
    category = item.get("category") or "未分类"
    price_yuan = item.get("priceYuan") or "未知"
    stock = item.get("stock")
    desc = item.get("description") or "暂无详细描述"
    return (
        f"# {name}\n\n"
        f"- 商品ID：{item.get('id')}\n"
        f"- 商品分类：{category}\n"
        f"- 当前售价：{price_yuan} 元\n"
        f"- 当前库存：{stock} 件\n"
        f"- 核心卖点与描述：{desc}\n"
    )


def _parse_mcp_json(raw: str, tool_name: str) -> dict[str, Any]:
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"调用 MCP 工具 {tool_name} 失败（请确认 Java business-api 已启动）: {raw}") from exc
    if not isinstance(payload, dict) or not payload.get("success"):
        raise RuntimeError(f"MCP 工具 {tool_name} 返回业务错误: {raw}")
    return payload


async def _fetch_all_on_sale_products(mcp=mcp_client) -> list[dict[str, Any]]:
    """遵循双语言隔离边界：通过 Java MCP 工具拉取所有在售商品，不直连 shop MySQL。"""
    cat_raw = await mcp.call_tool("list_categories", {})
    cat_payload = _parse_mcp_json(cat_raw, "list_categories")

    products: list[dict[str, Any]] = []
    seen_ids: set[int] = set()
    for cat_item in cat_payload.get("data") or []:
        category = cat_item.get("category")
        if not category:
            continue
        page = 1
        while True:
            search_raw = await mcp.call_tool(
                "search_products",
                {"category": category, "page": page, "size": 20},
            )
            search_payload = _parse_mcp_json(search_raw, "search_products")
            data = search_payload.get("data") or {}
            records = data.get("records") or data.get("items") or []
            for prod in records:
                pid = prod.get("id")
                if pid is not None and pid not in seen_ids:
                    seen_ids.add(pid)
                    products.append(prod)
            total = int(data.get("total", len(records)))
            if page * 20 >= total or not records:
                break
            page += 1
    return products


async def _ingest_single_doc(
    *,
    store: RagVectorStore,
    embedder: OllamaEmbedder,
    doc_key: str,
    source_type: str,
    title: str,
    category: str,
    source_path: str,
    text: str,
    metadata: dict[str, Any] | None = None,
) -> str:
    content_hash = compute_content_hash(text)
    existing_hash = await store.get_document_hash(doc_key)
    if existing_hash == content_hash:
        return "skipped"

    chunks = chunk_markdown(
        text,
        max_chars=config.RAG_CHUNK_MAX_CHARS,
        overlap_chars=config.RAG_CHUNK_OVERLAP_CHARS,
        fallback_title=title,
    )
    embeddings = await embedder.embed_texts([c.content for c in chunks])
    return await store.upsert_document_with_chunks(
        doc_key=doc_key,
        source_type=source_type,
        title=title,
        category=category,
        source_path=source_path,
        content_hash=content_hash,
        chunks=chunks,
        embeddings=embeddings,
        metadata=metadata,
    )


async def ingest_policies(
    store: RagVectorStore,
    embedder: OllamaEmbedder,
    policies_dir: Path = DEFAULT_POLICIES_DIR,
) -> dict[str, int]:
    """增量入库平台规则 Markdown 文档，并清理已删除文件对应的历史记录。"""
    counts = {"inserted": 0, "updated": 0, "skipped": 0, "deleted": 0}
    files = sorted(policies_dir.glob("*.md"))
    active_keys: set[str] = set()

    for path in files:
        doc_key = f"policy:{path.stem}"
        active_keys.add(doc_key)
        text = path.read_text(encoding="utf-8")
        title = extract_title(text, fallback=path.stem)
        status = await _ingest_single_doc(
            store=store,
            embedder=embedder,
            doc_key=doc_key,
            source_type="policy",
            title=title,
            category="平台规则",
            source_path=f"kb/policies/{path.name}",
            text=text,
            metadata={"filename": path.name},
        )
        counts[status] += 1

    counts["deleted"] = await store.delete_stale_documents("policy", active_keys)
    return counts


async def ingest_products(
    store: RagVectorStore,
    embedder: OllamaEmbedder,
    mcp=mcp_client,
) -> dict[str, int]:
    """通过 Java MCP 拉取在售商品目录并增量入库，同时自动清理已下架商品向量。"""
    counts = {"inserted": 0, "updated": 0, "skipped": 0, "deleted": 0}
    products = await _fetch_all_on_sale_products(mcp=mcp)
    active_keys: set[str] = set()

    for prod in products:
        pid = prod["id"]
        doc_key = f"product:{pid}"
        active_keys.add(doc_key)
        md_text = _format_product_markdown(prod)
        status = await _ingest_single_doc(
            store=store,
            embedder=embedder,
            doc_key=doc_key,
            source_type="product",
            title=prod.get("name") or doc_key,
            category=prod.get("category") or "",
            source_path=f"mcp://product/{pid}",
            text=md_text,
            metadata={"product_id": pid, "price_yuan": prod.get("priceYuan")},
        )
        counts[status] += 1

    counts["deleted"] = await store.delete_stale_documents("product", active_keys)
    return counts


async def run_ingest(*, policies: bool = True, products: bool = True) -> dict[str, Any]:
    store = RagVectorStore()
    embedder = OllamaEmbedder()
    summary: dict[str, Any] = {}
    if policies:
        summary["policies"] = await ingest_policies(store, embedder)
    if products:
        summary["products"] = await ingest_products(store, embedder)
    summary["stats"] = await store.stats()
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description="Ingest policies and on-sale products into pgvector.")
    parser.add_argument("--policies-only", action="store_true")
    parser.add_argument("--products-only", action="store_true")
    args = parser.parse_args()

    do_policies = not args.products_only
    do_products = not args.policies_only
    result = asyncio.run(run_ingest(policies=do_policies, products=do_products))
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
