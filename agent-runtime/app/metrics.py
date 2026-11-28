"""Phase 5 S4 轻量级监控指标收集器（同时支持 JSON 与 Prometheus 文本暴露格式）。"""
from collections import Counter
import time
from typing import Any


class RuntimeMetrics:
    def __init__(self):
        self.started_at = time.time()
        self.requests_total = 0
        self.degraded_total = 0
        self.guardrail_blocked_total = 0
        self.confirmation_required_total = 0
        self.confirmation_executed_total = 0
        self.tokens_total = 0
        self.latency_sum_s = 0.0
        self.route_counts: Counter[str] = Counter()
        self.guardrail_categories: Counter[str] = Counter()

    def record_chat(
        self,
        *,
        route: str,
        elapsed_s: float,
        usage: dict[str, Any] | None = None,
        degraded: bool = False,
        blocked_by_guardrail: str | None = None,
        pending_confirmation: dict[str, Any] | None = None,
        executed_confirmation: bool = False,
    ) -> None:
        self.requests_total += 1
        self.route_counts[route or "unknown"] += 1
        self.latency_sum_s += max(0.0, float(elapsed_s))
        self.tokens_total += int((usage or {}).get("total_tokens", 0))
        if degraded:
            self.degraded_total += 1
        if blocked_by_guardrail:
            self.guardrail_blocked_total += 1
            self.guardrail_categories[blocked_by_guardrail] += 1
        if pending_confirmation:
            self.confirmation_required_total += 1
        if executed_confirmation:
            self.confirmation_executed_total += 1

    def snapshot(self, breaker_snapshot: dict[str, Any] | None = None) -> dict[str, Any]:
        avg_latency = round(self.latency_sum_s / self.requests_total, 3) if self.requests_total else 0.0
        return {
            "uptime_s": round(time.time() - self.started_at, 1),
            "requests_total": self.requests_total,
            "degraded_total": self.degraded_total,
            "guardrail_blocked_total": self.guardrail_blocked_total,
            "confirmation_required_total": self.confirmation_required_total,
            "confirmation_executed_total": self.confirmation_executed_total,
            "tokens_total": self.tokens_total,
            "avg_latency_s": avg_latency,
            "route_counts": dict(self.route_counts),
            "guardrail_categories": dict(self.guardrail_categories),
            "llm_circuit_breaker": breaker_snapshot or {},
        }

    def to_prometheus(self, breaker_snapshot: dict[str, Any] | None = None) -> str:
        snap = self.snapshot(breaker_snapshot)
        cb_state = snap["llm_circuit_breaker"].get("state", "CLOSED")
        cb_val = {"CLOSED": 0, "HALF_OPEN": 1, "OPEN": 2}.get(cb_state, 0)
        lines = [
            "# HELP shop_copilot_agent_requests_total Total agent chat requests",
            "# TYPE shop_copilot_agent_requests_total counter",
            f"shop_copilot_agent_requests_total {snap['requests_total']}",
            "# HELP shop_copilot_agent_degraded_total Total requests handled by rule fallback",
            "# TYPE shop_copilot_agent_degraded_total counter",
            f"shop_copilot_agent_degraded_total {snap['degraded_total']}",
            "# HELP shop_copilot_guardrail_blocked_total Total requests blocked by input guardrails",
            "# TYPE shop_copilot_guardrail_blocked_total counter",
            f"shop_copilot_guardrail_blocked_total {snap['guardrail_blocked_total']}",
            "# HELP shop_copilot_confirmation_required_total Total high-risk writes paused for confirmation",
            "# TYPE shop_copilot_confirmation_required_total counter",
            f"shop_copilot_confirmation_required_total {snap['confirmation_required_total']}",
            "# HELP shop_copilot_llm_tokens_total Total LLM tokens consumed",
            "# TYPE shop_copilot_llm_tokens_total counter",
            f"shop_copilot_llm_tokens_total {snap['tokens_total']}",
            "# HELP shop_copilot_llm_circuit_breaker_state 0=CLOSED 1=HALF_OPEN 2=OPEN",
            "# TYPE shop_copilot_llm_circuit_breaker_state gauge",
            f"shop_copilot_llm_circuit_breaker_state {cb_val}",
        ]
        for route, count in sorted(snap["route_counts"].items()):
            lines.append(f'shop_copilot_route_requests_total{{route="{route}"}} {count}')
        return "\n".join(lines) + "\n"

    def reset(self) -> None:
        self.started_at = time.time()
        self.requests_total = 0
        self.degraded_total = 0
        self.guardrail_blocked_total = 0
        self.confirmation_required_total = 0
        self.confirmation_executed_total = 0
        self.tokens_total = 0
        self.latency_sum_s = 0.0
        self.route_counts.clear()
        self.guardrail_categories.clear()


metrics = RuntimeMetrics()
