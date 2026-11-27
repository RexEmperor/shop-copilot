"""Phase 5 S1 稳定性原语：三态熔断器、带抖动指数退避、流中断 Token 估算与规则兜底降级。"""
import json
import random
import re
import time
from typing import Any

import httpx

_CJK_RE = re.compile(r"[\u4e00-\u9fff]")


class CircuitOpenError(RuntimeError):
    """上游大模型熔断打开时快速失败抛出，避免雪崩式超时与重试风暴。"""


class CircuitBreaker:
    """三态熔断器（CLOSED -> OPEN -> HALF_OPEN）。

    - CLOSED: 正常放行；连续失败达到 failure_threshold 后切为 OPEN。
    - OPEN: 直接快速拒绝，不打上游；经过 recovery_timeout_s 后转为 HALF_OPEN。
    - HALF_OPEN: 仅放行 1 个探测请求；探测成功立刻复位 CLOSED，失败重新 OPEN。
    """

    def __init__(
        self,
        *,
        failure_threshold: int = 3,
        recovery_timeout_s: float = 30.0,
        clock=time.monotonic,
    ):
        self.failure_threshold = max(1, int(failure_threshold))
        self.recovery_timeout_s = max(0.1, float(recovery_timeout_s))
        self._clock = clock
        self._state = "CLOSED"
        self._consecutive_failures = 0
        self._opened_at = 0.0
        self._half_open_in_flight = False
        self._total_opened = 0

    @property
    def state(self) -> str:
        if self._state == "OPEN" and (self._clock() - self._opened_at) >= self.recovery_timeout_s:
            return "HALF_OPEN"
        return self._state

    def allow_request(self) -> bool:
        now = self._clock()
        if self._state == "CLOSED":
            return True
        if self._state == "OPEN":
            if (now - self._opened_at) >= self.recovery_timeout_s:
                self._state = "HALF_OPEN"
                self._half_open_in_flight = True
                return True
            return False
        # HALF_OPEN: 同一时刻只允许一个探测请求通过，防并发穿透
        if not self._half_open_in_flight:
            self._half_open_in_flight = True
            return True
        return False

    def record_success(self) -> None:
        self._state = "CLOSED"
        self._consecutive_failures = 0
        self._half_open_in_flight = False

    def record_failure(self) -> None:
        now = self._clock()
        if self._state == "HALF_OPEN":
            self._state = "OPEN"
            self._opened_at = now
            self._half_open_in_flight = False
            self._total_opened += 1
            return

        self._consecutive_failures += 1
        if self._consecutive_failures >= self.failure_threshold:
            self._state = "OPEN"
            self._opened_at = now
            self._half_open_in_flight = False
            self._total_opened += 1

    def reset(self) -> None:
        self._state = "CLOSED"
        self._consecutive_failures = 0
        self._opened_at = 0.0
        self._half_open_in_flight = False

    def snapshot(self) -> dict[str, Any]:
        return {
            "state": self.state,
            "consecutive_failures": self._consecutive_failures,
            "failure_threshold": self.failure_threshold,
            "recovery_timeout_s": self.recovery_timeout_s,
            "total_opened": self._total_opened,
        }


def compute_backoff_with_jitter(
    attempt: int,
    *,
    base_s: float = 0.5,
    cap_s: float = 4.0,
    rng=random.uniform,
) -> float:
    """带随机抖动（Equal Jitter）的指数退避，打散并发失败请求的重试波峰，防重试风暴。"""
    exp = min(cap_s, base_s * (2 ** max(0, attempt - 1)))
    return round(float(rng(exp * 0.5, exp)), 3)


def is_retryable_http_error(exc: Exception) -> bool:
    """区分瞬时可重试故障（超时、断连、429、5xx）与确定性客户端错误（400/401/403）。"""
    if isinstance(exc, (httpx.TimeoutException, httpx.ConnectError, httpx.RemoteProtocolError)):
        return True
    if isinstance(exc, httpx.HTTPStatusError):
        status = exc.response.status_code
        return status == 429 or status >= 500
    return False


def estimate_tokens_from_text(text: str) -> int:
    """零外部依赖的中英混合 Token 估算（流中途异常断开未收到官方 usage 时用于成本兜底）。"""
    if not text:
        return 0
    cjk_count = len(_CJK_RE.findall(text))
    non_cjk_count = max(0, len(text) - cjk_count)
    # 中文约 1 字 ≈ 1 token，英文/符号约 3 字符 ≈ 1 token
    return max(1, int(cjk_count + non_cjk_count / 3.0))


def estimate_tokens_from_messages(
    messages: list[dict[str, Any]], tools: list[dict[str, Any]] | None = None
) -> int:
    """估算发往大模型的输入 Prompt Token 数。"""
    parts: list[str] = []
    for msg in messages:
        parts.append(str(msg.get("role") or ""))
        content = msg.get("content")
        if content:
            parts.append(str(content))
        if msg.get("tool_calls"):
            parts.append(json.dumps(msg["tool_calls"], ensure_ascii=False))
    if tools:
        parts.append(json.dumps(tools, ensure_ascii=False))
    # 每条消息额外计 ~4 个结构包装 token
    return estimate_tokens_from_text("\n".join(parts)) + len(messages) * 4


def rule_based_fallback_reply(user_message: str, *, reason: str = "") -> str:
    """当大模型超时、故障或熔断打开时的规则兜底回复（确定性、零幻觉、不抛 500）。"""
    text = (user_message or "").strip()
    if any(kw in text for kw in ("人工", "投诉", "人工客服")):
        return (
            "当前智能对话引擎正在维护波动，您的转人工诉求我们已收到。"
            "人工在线客服工作时间为每日 09:00–22:00，请保持会话或留下订单号，客服同学将尽快为您处理。"
        )
    if any(kw in text for kw in ("你好", "您好", "在吗", "谢谢", "感谢")):
        return (
            "您好！欢迎来到智购助手。当前智能推理服务短暂繁忙，请稍等片刻再试；"
            "如有紧急订单或售后问题，也可直接回复“转人工”。"
        )
    if any(kw in text for kw in ("订单", "退款", "退货", "售后", "发货", "物流", "SO2026")):
        return (
            "抱歉，智能推理服务当前短暂波动，暂时无法自动查询或办理订单售后（我不会凭空猜测订单状态或金额）。"
            "建议您稍后重试，或直接回复“转人工”并提供订单号，由人工客服为您核实处理。"
        )
    if any(kw in text for kw in ("发票", "价保", "延保", "质保", "运费险", "七天", "赔付")):
        return (
            "抱歉，智能问答服务当前短暂波动，暂时无法调取完整规则条款（为避免误导，我不会凭空作答）。"
            "建议您 30 秒后重试，或回复“转人工”由人工客服为您准确解答。"
        )
    return (
        "抱歉，当前智能助手服务暂时繁忙（已自动触发降级保护，不会编造任何商品价格或订单结果）。"
        "建议您稍后重试，或回复“转人工”由人工客服继续协助您。"
    )
