"""Phase 5 S4 运行时监控指标（JSON + Prometheus 格式）单元测试。"""
from fastapi.testclient import TestClient

from app.main import app
from app.metrics import RuntimeMetrics, metrics


def test_runtime_metrics_snapshot_and_prometheus_format():
    m = RuntimeMetrics()
    m.record_chat(
        route="guide",
        elapsed_s=2.4,
        usage={"total_tokens": 1200},
    )
    m.record_chat(
        route="guardrail",
        elapsed_s=0.01,
        blocked_by_guardrail="prompt_injection",
    )
    m.record_chat(
        route="fallback",
        elapsed_s=1.2,
        degraded=True,
    )
    m.record_chat(
        route="order_aftersale",
        elapsed_s=3.0,
        pending_confirmation={"confirm_id": "abc"},
    )

    snap = m.snapshot({"state": "HALF_OPEN"})
    assert snap["requests_total"] == 4
    assert snap["degraded_total"] == 1
    assert snap["guardrail_blocked_total"] == 1
    assert snap["confirmation_required_total"] == 1
    assert snap["tokens_total"] == 1200
    assert snap["route_counts"]["guide"] == 1

    prom = m.to_prometheus({"state": "OPEN"})
    assert "shop_copilot_agent_requests_total 4" in prom
    assert "shop_copilot_agent_degraded_total 1" in prom
    assert "shop_copilot_guardrail_blocked_total 1" in prom
    assert "shop_copilot_llm_circuit_breaker_state 2" in prom
    assert 'shop_copilot_route_requests_total{route="guide"} 1' in prom


def test_metrics_endpoints_expose_json_and_prometheus():
    metrics.reset()
    metrics.record_chat(route="chitchat", elapsed_s=0.8, usage={"total_tokens": 500})

    client = TestClient(app)
    r_json = client.get("/api/metrics")
    assert r_json.status_code == 200
    data = r_json.json()
    assert data["requests_total"] == 1
    assert data["tokens_total"] == 500
    assert "state" in data["llm_circuit_breaker"]

    r_prom = client.get("/metrics")
    assert r_prom.status_code == 200
    assert "shop_copilot_agent_requests_total 1" in r_prom.text
