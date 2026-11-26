#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
source "$ROOT/env.sh"
set -a
source "$ROOT/infra/.env"
set +a

JAR="$ROOT/business-api/target/business-api-0.0.1-SNAPSHOT.jar"
LOG_FILE="/tmp/shop-copilot-bench.log"
APP_PID=""

cleanup() {
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

redis_cli() {
  docker compose --env-file "$ROOT/infra/.env" -f "$ROOT/infra/docker-compose.yml" exec -T redis redis-cli "$@"
}

clear_redis_prefix() {
  local pattern="$1"
  local keys
  keys=$(redis_cli --raw KEYS "$pattern" | tr -d '\r')
  if [[ -n "$keys" ]]; then
    while IFS= read -r key; do
      [[ -n "$key" ]] && redis_cli DEL "$key" >/dev/null
    done <<< "$keys"
  fi
}

start_app() {
  local rate_limit_enabled="$1"
  cleanup
  : > "$LOG_FILE"
  RATE_LIMIT_ENABLED="$rate_limit_enabled" java -jar "$JAR" > "$LOG_FILE" 2>&1 &
  APP_PID=$!
  for _ in $(seq 1 40); do
    if curl -sf http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep 0.5
  done
  echo "FAIL: business-api did not become healthy"
  tail -n 50 "$LOG_FILE"
  exit 1
}

if [[ ! -f "$JAR" ]]; then
  echo "Building business-api jar..."
  (cd "$ROOT/business-api" && mvn -q package -DskipTests)
fi

echo "=== Scenario 1: 缓存命中 & 500 条数据查询压测 (RATE_LIMIT_ENABLED=false) ==="
clear_redis_prefix "shop:product:detail:*"
clear_redis_prefix "shop:ratelimit:*"
start_app "false"

echo "--- OpenAPI /v3/api-docs 校验 ---"
curl -sf http://127.0.0.1:8080/v3/api-docs | python3 -c "
import json, sys
doc = json.load(sys.stdin)
paths = sorted(doc.get('paths', {}).keys())
print('OpenAPI paths:', len(paths), paths)
"

echo "--- 1A: 商品详情首次 MISS -> 二次 HIT 观测 ---"
curl -sf http://127.0.0.1:8080/api/products/1001 >/dev/null
curl -sf http://127.0.0.1:8080/api/products/1001 >/dev/null
echo "Redis TTL(shop:product:detail:1001): $(redis_cli TTL shop:product:detail:1001 | tr -d '\r')s"

echo "--- 1B: 商品详情热缓存压测 (ab -l -n 2000 -c 50) ---"
ab -l -n 2000 -c 50 http://127.0.0.1:8080/api/products/1001 | grep -E "Complete requests|Failed requests|Non-2xx|Requests per second|Time per request|50%|95%|99%"

echo "--- 1C: 商品分类分页查询压测 (MySQL 直查, ab -l -n 1000 -c 50) ---"
ab -l -n 1000 -c 50 'http://127.0.0.1:8080/api/products?category=%E6%95%B0%E7%A0%81&page=1&size=20' | grep -E "Complete requests|Failed requests|Non-2xx|Requests per second|Time per request|50%|95%|99%"

echo "--- 1D: 订单状态分页查询压测 (MySQL 直查, ab -l -n 1000 -c 50) ---"
ab -l -n 1000 -c 50 'http://127.0.0.1:8080/api/orders?status=1&page=1&size=20' | grep -E "Complete requests|Failed requests|Non-2xx|Requests per second|Time per request|50%|95%|99%"

MISS_COUNT=$(grep -c "Product cache MISS: productId=1001" "$LOG_FILE" || true)
HIT_COUNT=$(grep -c "Product cache HIT: productId=1001" "$LOG_FILE" || true)
ERR_COUNT=$(grep -c "系统异常" "$LOG_FILE" || true)
echo "Scenario 1 日志统计: productId=1001 MISS=${MISS_COUNT}, HIT=${HIT_COUNT}, 5xx系统异常=${ERR_COUNT}"

echo ""
echo "=== Scenario 2: 滑动窗口限流压测 (RATE_LIMIT_ENABLED=true, 商品详情 120次/60s) ==="
clear_redis_prefix "shop:ratelimit:*"
start_app "true"

ab -l -n 200 -c 20 http://127.0.0.1:8080/api/products/1001 | grep -E "Complete requests|Failed requests|Non-2xx|Requests per second|Time per request|50%|95%|99%"

ZCARD_VAL=$(redis_cli ZCARD "shop:ratelimit:ProductController#detail:127.0.0.1" | tr -d '\r')
REJECT_COUNT=$(grep -c "Rate limit REJECTED" "$LOG_FILE" || true)
ERR_COUNT_2=$(grep -c "系统异常" "$LOG_FILE" || true)
echo "Scenario 2 限流统计: Redis ZCARD=${ZCARD_VAL}, 日志 REJECTED=${REJECT_COUNT}, 5xx系统异常=${ERR_COUNT_2}"

# 清理压测产生的 Redis 临时键
clear_redis_prefix "shop:product:detail:*"
clear_redis_prefix "shop:ratelimit:*"
echo "=== Benchmark finished cleanly ==="
