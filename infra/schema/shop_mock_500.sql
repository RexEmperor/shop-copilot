-- Phase 2 S4：500 条商品 + 500 条订单 Mock 数据（幂等可重跑，独立 ID 段 1001~1500）
--
-- 说明：
--   1. 不改动 shop_seed.sql 的基础种子数据（product 1~10、orders 1~4、aftersale_ticket 1）。
--   2. 重复执行会先清理 1001~1500 号 mock 数据再重新生成，保证幂等。
--   3. 如需恢复到最小种子基线以运行 S1/S2 断言（如 verify_shop.sh / ProductApiIntegrationTest），执行：
--        DELETE FROM orders WHERE id BETWEEN 1001 AND 1500;
--        DELETE FROM product WHERE id BETWEEN 1001 AND 1500;

SET NAMES utf8mb4;
USE shop;
SET SESSION cte_max_recursion_depth = 1000;

DELETE FROM orders WHERE id BETWEEN 1001 AND 1500;
DELETE FROM product WHERE id BETWEEN 1001 AND 1500;

-- 1) 生成 500 条商品（ID 1001~1500，覆盖 6 大类目；每 20 条有 1 条下架，共 475 上架 / 25 下架）
INSERT INTO product (id, name, category, description, price_cents, stock, status, version, created_at, updated_at)
WITH RECURSIVE seq(n) AS (
  SELECT 1
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < 500
)
SELECT
  1000 + n AS id,
  CONCAT(
    ELT(MOD(n - 1, 6) + 1, '智选数码', '轻尚服饰', '甄选食品', '舒享家居', '活力运动', '悦己美妆'),
    '·',
    ELT(MOD(n - 1, 10) + 1, '旗舰版', '轻享版', 'Pro款', '经典款', '礼盒装', '升级款', '便携款', '基础款', '限定款', '套装版'),
    ' #',
    LPAD(n, 3, '0')
  ) AS name,
  ELT(MOD(n - 1, 6) + 1, '数码', '服饰', '食品', '家居', '运动', '美妆') AS category,
  CONCAT(
    'Phase 2 压测与检索用模拟商品 #', n,
    '，类目：', ELT(MOD(n - 1, 6) + 1, '数码', '服饰', '食品', '家居', '运动', '美妆'),
    '，支持 7 天无理由退换与极速发货。'
  ) AS description,
  (19 + MOD(n * 37, 2980)) * 100 AS price_cents,
  CASE WHEN MOD(n, 20) = 0 THEN 0 ELSE 20 + MOD(n * 13, 480) END AS stock,
  CASE WHEN MOD(n, 20) = 0 THEN 0 ELSE 1 END AS status,
  0 AS version,
  DATE_SUB('2026-11-26 12:00:00', INTERVAL (500 - n) MINUTE) AS created_at,
  DATE_SUB('2026-11-26 12:00:00', INTERVAL (500 - n) MINUTE) AS updated_at
FROM seq;

-- 2) 生成 500 条订单（ID 1001~1500，覆盖 0~4 全部五种状态，金额严格由商品单价 * 数量派生）
INSERT INTO orders (
  id, order_no, user_id, product_id, product_name_snapshot,
  unit_price_cents, quantity, total_amount_cents, status, version,
  receiver_name, receiver_phone, shipping_address,
  paid_at, shipped_at, delivered_at, cancelled_at, created_at, updated_at
)
WITH RECURSIVE seq(n) AS (
  SELECT 1
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < 500
)
SELECT
  1000 + s.n AS id,
  CONCAT('SOMOCK2026', LPAD(s.n, 4, '0')) AS order_no,
  10001 AS user_id,
  p.id AS product_id,
  p.name AS product_name_snapshot,
  p.price_cents AS unit_price_cents,
  (MOD(s.n - 1, 4) + 1) AS quantity,
  p.price_cents * (MOD(s.n - 1, 4) + 1) AS total_amount_cents,
  MOD(s.n - 1, 5) AS status,
  0 AS version,
  '郭克' AS receiver_name,
  CONCAT('1380000', LPAD(MOD(s.n, 10000), 4, '0')) AS receiver_phone,
  CONCAT('北京市朝阳区压测路', s.n, '号') AS shipping_address,
  CASE WHEN MOD(s.n - 1, 5) IN (1, 2, 3)
       THEN DATE_SUB('2026-11-26 12:00:00', INTERVAL (500 - s.n) MINUTE) + INTERVAL 5 MINUTE
       ELSE NULL END AS paid_at,
  CASE WHEN MOD(s.n - 1, 5) IN (2, 3)
       THEN DATE_SUB('2026-11-26 12:00:00', INTERVAL (500 - s.n) MINUTE) + INTERVAL 2 HOUR
       ELSE NULL END AS shipped_at,
  CASE WHEN MOD(s.n - 1, 5) = 3
       THEN DATE_SUB('2026-11-26 12:00:00', INTERVAL (500 - s.n) MINUTE) + INTERVAL 1 DAY
       ELSE NULL END AS delivered_at,
  CASE WHEN MOD(s.n - 1, 5) = 4
       THEN DATE_SUB('2026-11-26 12:00:00', INTERVAL (500 - s.n) MINUTE) + INTERVAL 10 MINUTE
       ELSE NULL END AS cancelled_at,
  DATE_SUB('2026-11-26 12:00:00', INTERVAL (500 - s.n) MINUTE) AS created_at,
  DATE_SUB('2026-11-26 12:00:00', INTERVAL (500 - s.n) MINUTE) AS updated_at
FROM seq s
JOIN product p ON p.id = 1000 + s.n;
