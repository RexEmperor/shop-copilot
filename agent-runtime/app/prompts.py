"""Prompt 模板管理:prompts/<名字>/<版本>.md,git 提交历史即版本史。

版本化收益:Phase 3 多 Agent 各有各的人格;Phase 5 评测可对 v1/v2 做 A/B 对比。
"""
from functools import lru_cache
from pathlib import Path

PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"


@lru_cache(maxsize=64)
def load_prompt(name: str, version: str = "v1") -> str:
    path = PROMPTS_DIR / name / f"{version}.md"
    if not path.exists():
        raise FileNotFoundError(f"prompt 不存在: {name}/{version}")
    return path.read_text(encoding="utf-8")