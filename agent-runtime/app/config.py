"""集中配置:全部来自环境变量(.env),代码里不出现魔法值"""
import os

from dotenv import load_dotenv

load_dotenv()

REDIS_URL = os.environ.get("REDIS_URL", "redis://localhost:6379/0")
HISTORY_MAX_MESSAGES = int(os.environ.get("HISTORY_MAX_MESSAGES", "20"))  # 送入模型的截断上限
HISTORY_TTL_DAYS = int(os.environ.get("HISTORY_TTL_DAYS", "7"))           # 会话过期时间
DATABASE_URL = os.environ["DATABASE_URL"]  # 必填,缺失就让它炸——fail fast
PROMPT_VERSION = os.environ.get("PROMPT_VERSION", "v1")