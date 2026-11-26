"""Markdown 标题感知切分器与内容哈希工具。"""
from dataclasses import dataclass
import hashlib
import re

_HEADING_RE = re.compile(r"^(#{1,3})\s+(.+?)\s*$")
_SENTENCE_SPLIT_RE = re.compile(r"(?<=[。！？；\n])")


@dataclass(frozen=True)
class ChunkPiece:
    chunk_index: int
    heading_path: str
    content: str
    token_estimate: int


def compute_content_hash(text: str) -> str:
    """计算规范化文本的 SHA-256 摘要，用于判断文档是否发生实质变更。"""
    normalized = text.replace("\r\n", "\n").strip()
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def extract_title(text: str, fallback: str = "") -> str:
    """提取 Markdown 一级标题；缺失时回退到 fallback。"""
    for line in text.splitlines():
        m = _HEADING_RE.match(line.strip())
        if m and len(m.group(1)) == 1:
            return m.group(2).strip()
    return fallback


def split_markdown_sections(text: str, fallback_title: str = "") -> list[tuple[str, str]]:
    """按 # / ## / ### 标题层级切分 Markdown，保留每个段落的完整标题路径。"""
    stack: list[str] = []
    sections: list[tuple[str, list[str]]] = []
    current_path = fallback_title
    current_lines: list[str] = []

    for raw_line in text.replace("\r\n", "\n").splitlines():
        m = _HEADING_RE.match(raw_line.strip())
        if m:
            body = "\n".join(current_lines).strip()
            if body:
                sections.append((current_path, [body]))
            current_lines = []

            level = len(m.group(1))
            heading_text = m.group(2).strip()
            stack = stack[: level - 1]
            stack.append(heading_text)
            current_path = " > ".join(stack)
        else:
            current_lines.append(raw_line)

    tail = "\n".join(current_lines).strip()
    if tail:
        sections.append((current_path or fallback_title, [tail]))

    return [(path, "\n".join(lines).strip()) for path, lines in sections if "\n".join(lines).strip()]


def _split_long_text(text: str, max_chars: int, overlap_chars: int) -> list[str]:
    """超长段落按句号/换行切分并保留重叠上下文，避免规则条款从句子中间截断。"""
    if len(text) <= max_chars:
        return [text]

    units = [u.strip() for u in _SENTENCE_SPLIT_RE.split(text) if u.strip()]
    if not units:
        units = [text]

    parts: list[str] = []
    buf = ""
    for unit in units:
        if len(unit) > max_chars:
            if buf:
                parts.append(buf.strip())
                buf = ""
            step = max(1, max_chars - max(0, overlap_chars))
            for i in range(0, len(unit), step):
                parts.append(unit[i : i + max_chars].strip())
            continue

        candidate = f"{buf}\n{unit}".strip() if buf else unit
        if len(candidate) <= max_chars:
            buf = candidate
        else:
            parts.append(buf.strip())
            tail = buf[-overlap_chars:].strip() if overlap_chars > 0 else ""
            buf = f"{tail}\n{unit}".strip() if tail and len(tail) + len(unit) + 1 <= max_chars else unit

    if buf:
        parts.append(buf.strip())
    return parts


def estimate_tokens(text: str) -> int:
    """中英混合文本粗略 token 估算（中文约 1 字 ≈ 1 token，英文按字符折算）。"""
    return max(1, int(len(text) * 0.85))


def chunk_markdown(
    text: str,
    *,
    max_chars: int = 450,
    overlap_chars: int = 60,
    fallback_title: str = "",
) -> list[ChunkPiece]:
    """将 Markdown 文档切分为带标题路径的语义分块列表。"""
    sections = split_markdown_sections(text, fallback_title=fallback_title)
    if not sections and text.strip():
        sections = [(fallback_title, text.strip())]

    pieces: list[ChunkPiece] = []
    idx = 0
    for heading_path, body in sections:
        prefix = f"[{heading_path}]\n" if heading_path else ""
        body_budget = max(120, max_chars - len(prefix))
        for sub in _split_long_text(body, body_budget, overlap_chars):
            full_text = f"{prefix}{sub}".strip()
            pieces.append(
                ChunkPiece(
                    chunk_index=idx,
                    heading_path=heading_path,
                    content=full_text,
                    token_estimate=estimate_tokens(full_text),
                )
            )
            idx += 1
    return pieces
