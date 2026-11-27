"""Phase 5 S1 稳定性与故障注入单元测试（熔断器、抖动退避、流中断估算、规则优雅降级）。"""
from contextlib import contextmanager
import json

import httpx
import pytest

from app.graph import workers, workflow
from app.llm import client as llm_mod
from app.llm.resilience import (
    CircuitBreaker,
    CircuitOpenError,
    compute_backoff_with_jitter,
    estimate_tokens_from_messages,
    estimate_tokens_from_text,
    rule_based_fallback_reply,
)


def test_circuit_breaker_lifecycle_closed_open_half_open_and_recover():
    now = [100.0]
    cb = CircuitBreaker(failure_threshold=2, recovery_timeout_s=30.0, clock=lambda: now[0])

    assert cb.state == "CLOSED"
    assert cb.allow_request() is True

    cb.record_failure()
    assert cb.state == "CLOSED"
    cb.record_failure()
    assert cb.state == "OPEN"
    assert cb.allow_request() is False  # 熔断期内快速拒绝

    # 过了恢复窗口，进入 HALF_OPEN，仅放行 1 个探测请求
    now[0] = 131.0
    assert cb.state == "HALF_OPEN"
    assert cb.allow_request() is True
    assert cb.allow_request() is False  # 并发探测拦截

    # 探测失败重新 OPEN
    cb.record_failure()
    assert cb.state == "OPEN"
    assert cb.snapshot()["total_opened"] == 2

    # 再次进入 HALF_OPEN 并探测成功 -> 复位 CLOSED
    now[0] = 165.0
    assert cb.allow_request() is True
    cb.record_success()
    assert cb.state == "CLOSED"
    assert cb.allow_request() is True


def test_jitter_backoff_and_token_estimation_and_rule_fallback():
    b1 = compute_backoff_with_jitter(1, base_s=0.5, cap_s=4.0, rng=lambda lo, hi: lo)
    b2 = compute_backoff_with_jitter(4, base_s=0.5, cap_s=4.0, rng=lambda lo, hi: hi)
    assert b1 == 0.25
    assert b2 == 4.0

    est = estimate_tokens_from_messages(
        [{"role": "user", "content": "帮我查一下订单 SO202611240002"}],
        tools=[{"type": "function", "function": {"name": "list_orders"}}],
    )
    assert est > 15
    assert estimate_tokens_from_text("你好世界") >= 4

    assert "订单" in rule_based_fallback_reply("帮我查订单 SO202611240002")
    assert "转人工" in rule_based_fallback_reply("延保多少钱")
    assert "09:00" in rule_based_fallback_reply("我要转人工投诉")


async def test_client_retries_503_skips_400_and_opens_breaker(monkeypatch):
    sleeps: list[float] = []

    async def fake_sleep(s: float):
        sleeps.append(s)

    monkeypatch.setattr(llm_mod.asyncio, "sleep", fake_sleep)

    # 场景 1：首次 503，重试一次成功
    calls = {"n": 0}

    def handler_flaky(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] == 1:
            return httpx.Response(503, json={"error": "overloaded"}, request=request)
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"role": "assistant", "content": "ok"}}],
                "usage": {"prompt_tokens": 10, "completion_tokens": 2, "total_tokens": 12},
            },
            request=request,
        )

    transport = httpx.MockTransport(handler_flaky)
    orig_client = httpx.AsyncClient

    class PatchedClient(orig_client):
        def __init__(self, *args, **kwargs):
            kwargs["transport"] = transport
            super().__init__(*args, **kwargs)

    monkeypatch.setattr(llm_mod.httpx, "AsyncClient", PatchedClient)

    cb = CircuitBreaker(failure_threshold=2, recovery_timeout_s=30.0)
    c = llm_mod.DeepSeekClient(api_key="test-key", max_retries=1, breaker=cb)
    out = await c.chat([{"role": "user", "content": "hi"}])
    assert out["content"] == "ok"
    assert calls["n"] == 2
    assert len(sleeps) == 1
    assert cb.state == "CLOSED"

    # 场景 2：确定性 400 错误不重试，也不触发熔断
    calls_400 = {"n": 0}

    def handler_400(request: httpx.Request) -> httpx.Response:
        calls_400["n"] += 1
        return httpx.Response(400, json={"error": "bad request"}, request=request)

    t400 = httpx.MockTransport(handler_400)

    class Patched400(orig_client):
        def __init__(self, *args, **kwargs):
            kwargs["transport"] = t400
            super().__init__(*args, **kwargs)

    monkeypatch.setattr(llm_mod.httpx, "AsyncClient", Patched400)
    with pytest.raises(RuntimeError):
        await c.chat([{"role": "user", "content": "bad"}])
    assert calls_400["n"] == 1
    assert cb.state == "CLOSED"

    # 场景 3：连续超时击穿熔断阈值 -> 下一次调用直接 CircuitOpenError 零重试快速失败
    def handler_timeout(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("timeout", request=request)

    t_out = httpx.MockTransport(handler_timeout)

    class PatchedTimeout(orig_client):
        def __init__(self, *args, **kwargs):
            kwargs["transport"] = t_out
            super().__init__(*args, **kwargs)

    monkeypatch.setattr(llm_mod.httpx, "AsyncClient", PatchedTimeout)
    with pytest.raises(RuntimeError):
        await c.chat([{"role": "user", "content": "t1"}])
    with pytest.raises(RuntimeError):
        await c.chat([{"role": "user", "content": "t2"}])
    assert cb.state == "OPEN"

    with pytest.raises(CircuitOpenError):
        await c.chat([{"role": "user", "content": "t3"}])


async def test_chat_stream_aborted_midway_reports_estimated_tokens(monkeypatch):
    """流中途异常中断未拿到官方 usage 时，finally 必须估算并补报已消耗 token。"""
    recorded_updates: list[dict] = []

    class FakeGen:
        def update(self, **kwargs):
            recorded_updates.append(kwargs)

    class FakeLangfuse:
        @contextmanager
        def start_as_current_observation(self, **kwargs):
            yield FakeGen()

    monkeypatch.setattr(llm_mod, "get_client", lambda: FakeLangfuse())

    class BrokenStreamResp:
        def raise_for_status(self):
            return None

        async def aiter_lines(self):
            yield 'data: {"choices":[{"delta":{"content":"这是前半段"}}]}'
            yield 'data: {"choices":[{"delta":{"content":"回答内容"}}]}'
            raise httpx.ReadTimeout("mid-stream disconnect")

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

    class FakeAsyncClient:
        def __init__(self, *args, **kwargs):
            pass

        def stream(self, *args, **kwargs):
            return BrokenStreamResp()

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

    monkeypatch.setattr(llm_mod.httpx, "AsyncClient", FakeAsyncClient)

    cb = CircuitBreaker(failure_threshold=2)
    c = llm_mod.DeepSeekClient(api_key="test-key", breaker=cb)
    got_deltas = []
    with pytest.raises(RuntimeError, match="流式调用中断"):
        async for ev in c.chat_stream([{"role": "user", "content": "请问七天无理由退货规则是什么？"}]):
            if ev["type"] == "delta":
                got_deltas.append(ev["content"])

    assert "".join(got_deltas) == "这是前半段回答内容"
    assert len(recorded_updates) == 1
    upd = recorded_updates[0]
    assert upd["metadata"] == {"stream_aborted": True, "usage_estimated": True}
    assert upd["usage_details"]["input"] > 0
    assert upd["usage_details"]["output"] > 0
    assert upd["usage_details"]["total"] == (
        upd["usage_details"]["input"] + upd["usage_details"]["output"]
    )


async def test_graph_degrades_gracefully_on_llm_timeout_and_circuit_open(monkeypatch):
    """人为注入大模型超时/熔断故障，状态机（同步+流式）必须优雅走规则兜底而不崩溃。"""
    saved: list[tuple[str, str]] = []

    async def fake_get_history(sid):
        return [{"role": role, "content": content} for role, content in saved]

    async def fake_append_message(sid, role, content, **kwargs):
        saved.append((role, content))

    monkeypatch.setattr(workflow, "get_history", fake_get_history)
    monkeypatch.setattr(workflow, "append_message", fake_append_message)

    class DeadLLM:
        model = "deepseek-chat"

        async def chat(self, messages, tools=None):
            raise CircuitOpenError("circuit open")

        async def chat_stream(self, messages, tools=None):
            raise CircuitOpenError("circuit open")
            yield  # pragma: no cover

    res = await workflow.run_graph("fault-sess-01", "帮我查下订单 SO202611240002", llm_client=DeadLLM())
    assert res["degraded"] is True
    assert res["handoff_requested"] is False
    assert "订单" in res["content"] and "转人工" in res["content"]

    # 流式路径同样在路由或 Worker 失败时推送 delta + final(degraded=True)
    saved.clear()
    events = [
        ev async for ev in workflow.run_graph_stream("fault-sess-02", "延保多少钱", llm_client=DeadLLM())
    ]
    types = [e["type"] for e in events]
    assert "route" in types
    assert "delta" in types
    assert types[-1] == "final"
    assert events[-1]["degraded"] is True
    assert "转人工" in events[-1]["content"]

    # Worker 阶段突然熔断（Router 成功，Worker 失败）同样优雅降级
    saved.clear()
    step = {"n": 0}

    class WorkerFailsLLM:
        model = "deepseek-chat"

        async def chat(self, messages, tools=None):
            step["n"] += 1
            if step["n"] == 1:
                return {
                    "content": json.dumps({"route": "guide", "plan": ["guide"], "reason": "选品"}),
                    "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
                }
            raise RuntimeError("upstream 503")

    monkeypatch.setattr(workers, "all_schemas", lambda **kwargs: _async_val([]))
    res2 = await workflow.run_graph("fault-sess-03", "推荐个耳机", llm_client=WorkerFailsLLM())
    assert res2["degraded"] is True
    assert "降级保护" in res2["content"] or "繁忙" in res2["content"]


async def _async_val(v):
    return v
