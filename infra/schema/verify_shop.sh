#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
ENV_FILE="${ENV_FILE:-$ROOT/infra/.env}"
set -a
source "$ENV_FILE"
set +a

MYSQL=(docker compose --env-file "$ENV_FILE" -f "$ROOT/infra/docker-compose.yml" exec -T mysql env MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names shop)

query() {
  "${MYSQL[@]}" -e "$1"
}

assert_eq() {
  local label="$1" expected="$2" actual="$3"
  if [[ "$actual" != "$expected" ]]; then
    printf 'FAIL %-42s expected=%s actual=%s\n' "$label" "$expected" "$actual"
    exit 1
  fi
  printf 'PASS %s\n' "$label"
}

assert_eq 'three business tables exist' 3 "$(query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('product','orders','aftersale_ticket')")"
assert_eq 'all tables use utf8mb4' 3 "$(query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('product','orders','aftersale_ticket') AND table_collation LIKE 'utf8mb4%'")"
assert_eq 'all tables have comments' 3 "$(query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('product','orders','aftersale_ticket') AND table_comment <> ''")"
assert_eq 'all columns have comments' 0 "$(query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('product','orders','aftersale_ticket') AND column_comment = ''")"
assert_eq 'updated_at auto-updates on all tables' 3 "$(query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('product','orders','aftersale_ticket') AND column_name='updated_at' AND LOWER(extra) LIKE '%on update current_timestamp%'")"
assert_eq 'product and orders have version default 0' 2 "$(query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('product','orders') AND column_name='version' AND data_type='int' AND column_default='0'")"
assert_eq 'orders has product_id index' 1 "$(query "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='orders' AND index_name='idx_product_id' AND column_name='product_id'")"
assert_eq 'status and aftersale type checks exist' 4 "$(query "SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_type='CHECK' AND constraint_name IN ('chk_product_status','chk_order_status','chk_aftersale_type','chk_aftersale_status')")"
assert_eq 'orders.order_no is unique' 1 "$(query "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='orders' AND index_name='uk_order_no' AND non_unique=0")"
assert_eq 'orders has user_id,status index' 1 "$(query "SELECT COUNT(*) FROM (SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='orders' GROUP BY index_name HAVING cols='user_id,status') AS indexes_found")"
assert_eq 'product has category,status index' 1 "$(query "SELECT COUNT(*) FROM (SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='product' GROUP BY index_name HAVING cols='category,status') AS indexes_found")"
assert_eq 'aftersale has order_no,status index' 1 "$(query "SELECT COUNT(*) FROM (SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='aftersale_ticket' GROUP BY index_name HAVING cols='order_no,status') AS indexes_found")"
assert_eq 'no physical foreign keys' 0 "$(query "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE()")"
assert_eq 'money columns use bigint cents' 4 "$(query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name IN ('price_cents','unit_price_cents','total_amount_cents','refund_cents') AND data_type='bigint'")"
assert_eq '10 products seeded' 10 "$(query 'SELECT COUNT(*) FROM product')"
assert_eq 'seeded products start at version 0' 10 "$(query 'SELECT COUNT(*) FROM product WHERE version=0')"
assert_eq '1 off-sale product seeded' 1 "$(query 'SELECT COUNT(*) FROM product WHERE status=0')"
assert_eq '4 orders seeded' 4 "$(query 'SELECT COUNT(*) FROM orders')"
assert_eq 'seeded orders start at version 0' 4 "$(query 'SELECT COUNT(*) FROM orders WHERE version=0')"
assert_eq '4 order states covered' 4 "$(query 'SELECT COUNT(*) FROM (SELECT status FROM orders GROUP BY status HAVING status IN (0,1,2,3)) AS states_found')"
assert_eq 'order totals match unit price times quantity' 0 "$(query 'SELECT COUNT(*) FROM orders WHERE total_amount_cents <> unit_price_cents * quantity')"
assert_eq '1 aftersale seeded' 1 "$(query 'SELECT COUNT(*) FROM aftersale_ticket')"
assert_eq 'rejected aftersale attached to paid order' 1 "$(query "SELECT COUNT(*) FROM aftersale_ticket a JOIN orders o ON o.order_no=a.order_no WHERE a.status=3 AND o.status=1 AND a.review_remark IS NOT NULL AND a.reviewed_at IS NOT NULL AND a.refunded_at IS NULL")"
assert_eq 'aftersale refund matches order total' 0 "$(query 'SELECT COUNT(*) FROM aftersale_ticket a JOIN orders o ON o.order_no=a.order_no WHERE a.refund_cents <> o.total_amount_cents')"
printf 'All shop schema checks passed.\n'
