#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
ENV_FILE="${ENV_FILE:-$ROOT/infra/.env}"
set -a
source "$ENV_FILE"
set +a

PSQL=(docker compose --env-file "$ENV_FILE" -f "$ROOT/infra/docker-compose.yml" exec -T postgres env PGPASSWORD="$PG_PASSWORD" psql -U postgres -d rag -tA)

query() {
  "${PSQL[@]}" -c "$1"
}

assert_eq() {
  local label="$1" expected="$2" actual="$3"
  if [[ "$actual" != "$expected" ]]; then
    printf 'FAIL %-44s expected=%s actual=%s\n' "$label" "$expected" "$actual"
    exit 1
  fi
  printf 'PASS %s\n' "$label"
}

assert_eq 'pgvector extension enabled' 1 "$(query "SELECT COUNT(*) FROM pg_extension WHERE extname='vector'")"
assert_eq 'kb_document and kb_chunk tables exist' 2 "$(query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('kb_document','kb_chunk')")"
assert_eq 'kb_document has unique doc_key' 1 "$(query "SELECT COUNT(*) FROM pg_indexes WHERE schemaname='public' AND tablename='kb_document' AND indexdef LIKE '%UNIQUE%' AND indexdef LIKE '%(doc_key)%'")"
assert_eq 'kb_chunk embedding uses vector(1024)' 'vector(1024)' "$(query "SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid WHERE c.relname='kb_chunk' AND a.attname='embedding'")"
assert_eq 'kb_chunk has HNSW cosine index' 1 "$(query "SELECT COUNT(*) FROM pg_indexes WHERE schemaname='public' AND tablename='kb_chunk' AND indexname='idx_kb_chunk_embedding_hnsw' AND indexdef LIKE '%USING hnsw%' AND indexdef LIKE '%vector_cosine_ops%'")"
assert_eq 'kb_chunk has cascade FK to kb_document' 1 "$(query "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='public' AND delete_rule='CASCADE'")"
assert_eq 'kb_chunk unique (document_id, chunk_index)' 1 "$(query "SELECT COUNT(*) FROM pg_indexes WHERE schemaname='public' AND tablename='kb_chunk' AND indexname='uk_kb_chunk_doc_idx'")"
printf 'All rag schema checks passed.\n'
