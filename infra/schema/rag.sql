-- shop-copilot Phase 4 RAG 向量知识库 DDL (PostgreSQL 16 + pgvector)
-- 用法: docker exec -i sc-pg psql -U postgres -d rag < infra/schema/rag.sql

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS kb_document (
    id           BIGSERIAL    PRIMARY KEY,
    doc_key      VARCHAR(128) NOT NULL UNIQUE,
    source_type  VARCHAR(32)  NOT NULL CHECK (source_type IN ('policy', 'product')),
    title        VARCHAR(200) NOT NULL,
    category     VARCHAR(64)  NOT NULL DEFAULT '',
    source_path  VARCHAR(255) NOT NULL DEFAULT '',
    content_hash CHAR(64)     NOT NULL,
    metadata     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    chunk_count  INT          NOT NULL DEFAULT 0 CHECK (chunk_count >= 0),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE kb_document IS 'RAG 知识库文档元数据表（支持按 content_hash 幂等增量更新）';
COMMENT ON COLUMN kb_document.doc_key IS '文档唯一业务键，如 policy:01_return_7days 或 product:1';
COMMENT ON COLUMN kb_document.source_type IS '来源类型：policy-平台规则文档，product-在售商品目录';
COMMENT ON COLUMN kb_document.title IS '文档展示标题（用于回答引用溯源）';
COMMENT ON COLUMN kb_document.category IS '文档业务分类';
COMMENT ON COLUMN kb_document.source_path IS '原始文件路径或业务来源标识';
COMMENT ON COLUMN kb_document.content_hash IS '规范化正文 SHA-256 哈希，用于判断文档是否变更';
COMMENT ON COLUMN kb_document.metadata IS '扩展元数据 JSONB（如 product_id、version）';
COMMENT ON COLUMN kb_document.chunk_count IS '当前有效分块数量';

CREATE INDEX IF NOT EXISTS idx_kb_document_source_type ON kb_document (source_type);
CREATE INDEX IF NOT EXISTS idx_kb_document_category ON kb_document (category);

CREATE TABLE IF NOT EXISTS kb_chunk (
    id             BIGSERIAL    PRIMARY KEY,
    document_id    BIGINT       NOT NULL REFERENCES kb_document(id) ON DELETE CASCADE,
    chunk_index    INT          NOT NULL CHECK (chunk_index >= 0),
    heading_path   VARCHAR(255) NOT NULL DEFAULT '',
    content        TEXT         NOT NULL,
    token_estimate INT          NOT NULL DEFAULT 0 CHECK (token_estimate >= 0),
    embedding      vector(1024) NOT NULL,
    metadata       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_kb_chunk_doc_idx UNIQUE (document_id, chunk_index)
);

COMMENT ON TABLE kb_chunk IS 'RAG 知识库语义分块与 BGE-M3 1024 维向量表';
COMMENT ON COLUMN kb_chunk.document_id IS '关联 kb_document.id（级联删除防孤儿分块）';
COMMENT ON COLUMN kb_chunk.chunk_index IS '文档内分块序号（从 0 开始）';
COMMENT ON COLUMN kb_chunk.heading_path IS 'Markdown 标题层级路径（如 七天无理由退货规则 > 不适用场景）';
COMMENT ON COLUMN kb_chunk.content IS '分块正文内容';
COMMENT ON COLUMN kb_chunk.token_estimate IS '估算 token 长度';
COMMENT ON COLUMN kb_chunk.embedding IS 'Ollama bge-m3 生成的 1024 维稠密向量';
COMMENT ON COLUMN kb_chunk.metadata IS '分块扩展元数据 JSONB';

CREATE INDEX IF NOT EXISTS idx_kb_chunk_document_id ON kb_chunk (document_id);
CREATE INDEX IF NOT EXISTS idx_kb_chunk_embedding_hnsw
    ON kb_chunk USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
