"""集中配置:全部来自环境变量(.env),代码里不出现魔法值"""
import os

from dotenv import load_dotenv

load_dotenv()

REDIS_URL = os.environ.get("REDIS_URL", "redis://localhost:6379/0")
HISTORY_MAX_MESSAGES = int(os.environ.get("HISTORY_MAX_MESSAGES", "20"))  # 送入模型的截断上限
HISTORY_TTL_DAYS = int(os.environ.get("HISTORY_TTL_DAYS", "7"))           # 会话过期时间
DATABASE_URL = os.environ["DATABASE_URL"]  # 必填,缺失就让它炸——fail fast
PROMPT_VERSION = os.environ.get("PROMPT_VERSION", "v1")

BUSINESS_API_BASE = os.environ.get("BUSINESS_API_BASE", "http://localhost:8080")
MCP_SERVER_URL = os.environ.get("MCP_SERVER_URL", f"{BUSINESS_API_BASE}/mcp")
MCP_ENABLED = os.environ.get("MCP_ENABLED", "true").lower() in {"1", "true", "yes"}
MCP_TIMEOUT_SECONDS = float(os.environ.get("MCP_TIMEOUT_SECONDS", "10.0"))
MCP_MAX_RETRIES = int(os.environ.get("MCP_MAX_RETRIES", "1"))
MCP_TOOLS_CACHE_TTL_SECONDS = float(os.environ.get("MCP_TOOLS_CACHE_TTL_SECONDS", "60.0"))

# Phase 4 RAG 配置（PostgreSQL pgvector + 本地 Ollama BGE-M3）
RAG_DATABASE_URL = os.environ.get("RAG_DATABASE_URL", "")
OLLAMA_BASE_URL = os.environ.get("OLLAMA_BASE_URL", "http://127.0.0.1:11434")
OLLAMA_EMBED_MODEL = os.environ.get("OLLAMA_EMBED_MODEL", "bge-m3")
OLLAMA_TIMEOUT_SECONDS = float(os.environ.get("OLLAMA_TIMEOUT_SECONDS", "30.0"))
RAG_EMBED_DIM = 1024
RAG_CHUNK_MAX_CHARS = int(os.environ.get("RAG_CHUNK_MAX_CHARS", "450"))
RAG_CHUNK_OVERLAP_CHARS = int(os.environ.get("RAG_CHUNK_OVERLAP_CHARS", "60"))
RAG_TOP_K = int(os.environ.get("RAG_TOP_K", "3"))
RAG_CANDIDATE_K = int(os.environ.get("RAG_CANDIDATE_K", "8"))
RAG_RRF_K = int(os.environ.get("RAG_RRF_K", "60"))
RAG_MIN_VECTOR_SCORE = float(os.environ.get("RAG_MIN_VECTOR_SCORE", "0.45"))
RAG_POLICY_MIN_VECTOR_SCORE = float(os.environ.get("RAG_POLICY_MIN_VECTOR_SCORE", "0.55"))
SILICONFLOW_API_KEY = os.environ.get("SILICONFLOW_API_KEY", "")
SILICONFLOW_RERANK_MODEL = os.environ.get("SILICONFLOW_RERANK_MODEL", "BAAI/bge-reranker-v2-m3")