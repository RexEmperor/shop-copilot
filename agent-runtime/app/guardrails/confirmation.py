"""资金类写操作（如 create_aftersale 发起退款）强制二次确认与幂等防重状态管理。"""
import hashlib
import json
import logging
import re
import time
from typing import Any
import uuid

logger = logging.getLogger("guardrails.confirmation")

# 需要强制二次确认的资金/高危写工具集合
HIGH_RISK_WRITE_TOOLS = frozenset({"create_aftersale"})

_CONFIRM_RE = re.compile(
    r"^(确认|确定|确认提交|确定提交|是的[，,\s]*确认|好的[，,\s]*确认|同意提交|确认申请|confirm|yes)$",
    re.IGNORECASE,
)
_CANCEL_RE = re.compile(
    r"^(取消|撤销|算了|不退了|不申请了|不用了|取消提交|cancel|no)$",
    re.IGNORECASE,
)

_AFTERSALE_TYPE_LABELS = {
    0: "仅退款",
    1: "退货退款",
}


def compute_idempotency_key(session_id: str, tool_name: str, args: dict[str, Any]) -> str:
    canonical = json.dumps(
        {"session_id": session_id, "tool": tool_name, "args": args},
        sort_keys=True,
        ensure_ascii=False,
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16]


def classify_confirmation_reply(user_message: str) -> str | None:
    """识别用户是否明确回复“确认提交”或“取消”；非明确表态返回 None。"""
    text = (user_message or "").strip().strip("！!。.，, ")
    if _CONFIRM_RE.match(text):
        return "confirm"
    if _CANCEL_RE.match(text):
        return "cancel"
    return None


def format_action_summary(tool_name: str, args: dict[str, Any]) -> dict[str, Any]:
    if tool_name == "create_aftersale":
        raw_type = args.get("type", 0)
        try:
            type_code = int(raw_type)
        except (TypeError, ValueError):
            type_code = 0
        return {
            "tool": tool_name,
            "orderNo": args.get("orderNo") or "未知订单号",
            "aftersaleType": _AFTERSALE_TYPE_LABELS.get(type_code, str(raw_type)),
            "typeCode": type_code,
            "reason": args.get("reason") or "未填写原因",
        }
    return {"tool": tool_name, "args": dict(args)}


class ConfirmationManager:
    """管理会话级待确认资金操作与幂等去重记录（优先 Redis，Redis 不可用时自动降级进程内存）。"""

    def __init__(
        self,
        *,
        redis_client=None,
        pending_ttl_s: int = 300,
        idempotency_ttl_s: int = 600,
    ):
        self._redis = redis_client
        self.pending_ttl_s = pending_ttl_s
        self.idempotency_ttl_s = idempotency_ttl_s
        self._mem_pending: dict[str, tuple[float, dict[str, Any]]] = {}
        self._mem_executed: dict[str, tuple[float, str]] = {}

    def _pending_key(self, session_id: str) -> str:
        return f"guard:pending:{session_id}"

    def _idem_key(self, idempotency_key: str) -> str:
        return f"guard:idem:{idempotency_key}"

    async def create_pending(
        self, session_id: str, tool_name: str, args: dict[str, Any]
    ) -> dict[str, Any]:
        confirm_id = uuid.uuid4().hex[:8]
        idem_key = compute_idempotency_key(session_id, tool_name, args)
        record = {
            "confirm_id": confirm_id,
            "idempotency_key": idem_key,
            "tool": tool_name,
            "args": dict(args),
            "summary": format_action_summary(tool_name, args),
            "created_at": time.time(),
        }
        payload = json.dumps(record, ensure_ascii=False)
        if self._redis is not None:
            try:
                await self._redis.set(self._pending_key(session_id), payload, ex=self.pending_ttl_s)
                return record
            except Exception as exc:
                logger.warning("Redis set pending failed, fallback to memory: %r", exc)
        self._mem_pending[session_id] = (time.monotonic() + self.pending_ttl_s, record)
        return record

    async def get_pending(self, session_id: str) -> dict[str, Any] | None:
        if self._redis is not None:
            try:
                raw = await self._redis.get(self._pending_key(session_id))
                if raw:
                    return json.loads(raw)
            except Exception as exc:
                logger.warning("Redis get pending failed, fallback to memory: %r", exc)
        item = self._mem_pending.get(session_id)
        if not item:
            return None
        expires_at, record = item
        if time.monotonic() > expires_at:
            self._mem_pending.pop(session_id, None)
            return None
        return dict(record)

    async def clear_pending(self, session_id: str) -> None:
        self._mem_pending.pop(session_id, None)
        if self._redis is not None:
            try:
                await self._redis.delete(self._pending_key(session_id))
            except Exception as exc:
                logger.warning("Redis delete pending failed: %r", exc)

    async def acquire_idempotency(self, idempotency_key: str) -> bool:
        """原子占位幂等键：首次返回 True（允许执行），重复提交返回 False（防重拦截）。"""
        if self._redis is not None:
            try:
                acquired = await self._redis.set(
                    self._idem_key(idempotency_key),
                    "EXECUTING",
                    nx=True,
                    ex=self.idempotency_ttl_s,
                )
                return bool(acquired)
            except Exception as exc:
                logger.warning("Redis acquire idempotency failed, fallback to memory: %r", exc)

        now = time.monotonic()
        existing = self._mem_executed.get(idempotency_key)
        if existing and now <= existing[0]:
            return False
        self._mem_executed[idempotency_key] = (now + self.idempotency_ttl_s, "EXECUTING")
        return True

    async def save_executed_result(self, idempotency_key: str, result_text: str) -> None:
        self._mem_executed[idempotency_key] = (
            time.monotonic() + self.idempotency_ttl_s,
            result_text,
        )
        if self._redis is not None:
            try:
                await self._redis.set(
                    self._idem_key(idempotency_key),
                    result_text,
                    ex=self.idempotency_ttl_s,
                )
            except Exception as exc:
                logger.warning("Redis save idempotency result failed: %r", exc)

    def clear_memory(self) -> None:
        """测试清理用。"""
        self._mem_pending.clear()
        self._mem_executed.clear()


from app.chat import redis as _default_redis  # noqa: E402

confirmation_manager = ConfirmationManager(redis_client=_default_redis)
