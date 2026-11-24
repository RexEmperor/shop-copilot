# S2d 售后模块契约

- 状态：已确认
- 确认日期：2026-11-25
- 适用范围：Phase 2 / S2d 售后模块
- 前置约定：沿用一单一商品、金额存分、固定 `user_id=10001`、MyBatis-Plus、统一 `Result<T>` 响应。

## 1. 已确认的业务取舍

1. 区分退款处理中与退款完成，不把审核通过直接视为退款完成。
2. 审核通过后立即进入退款中，不保留独立的“已通过”状态。
3. 同一订单允许存在多条历史工单，但进行中的工单最多一条；拒绝后允许重新发起。

## 2. 状态机

```text
PENDING ──approve──> REFUNDING ──refund/complete──> REFUNDED
   │
   └────reject─────> REJECTED
```

| 状态 | code | 终态 | 说明 |
|---|---:|---|---|
| `PENDING` | 0 | 否 | 待审核 |
| `REFUNDING` | 1 | 否 | 审核通过，退款处理中 |
| `REFUNDED` | 2 | 是 | 退款完成 |
| `REJECTED` | 3 | 是 | 审核拒绝，允许重新发起售后 |

- 本期“确认退款”用于模拟退款渠道回调，不接真实支付。
- 不模拟退款失败、重试；真实支付接入时再扩展。

## 3. 接口契约

统一使用现有 `Result<T>`，成功返回 HTTP 200。

| 方法 | 路径 | 入参 | 返回 |
|---|---|---|---|
| `POST` | `/api/aftersales` | `orderNo`、`type`、`reason` | 售后工单 ID |
| `GET` | `/api/aftersales` | `orderNo?`、`status?`、`page`、`size` | 分页售后列表 |
| `GET` | `/api/aftersales/{id}` | 路径 ID | 售后详情 |
| `POST` | `/api/aftersales/{id}/approve` | 无 | 更新后的售后详情 |
| `POST` | `/api/aftersales/{id}/reject` | `reviewRemark` | 更新后的售后详情 |
| `POST` | `/api/aftersales/{id}/refund/complete` | 无 | 更新后的售后详情 |

### 创建售后

`POST /api/aftersales`

```json
{
  "orderNo": "SO202611240002",
  "type": 0,
  "reason": "商品与描述不符"
}
```

- `orderNo`：必填，订单业务号。
- `type`：必填，`0=仅退款`，`1=退货退款`。
- `reason`：必填，最长 500 字。
- 拒绝售后时 `reviewRemark` 必填，最长 500 字。
- 不接受客户端传入 `refundCents`、`userId`、`status`。

### 分页查询

`GET /api/aftersales?orderNo=SO202611240002&status=0&page=1&size=20`

- `orderNo`：可选，按订单号精确过滤。
- `status`：可选，按售后状态过滤。
- 分页参数复用现有 `PageQuery` 默认值与上限。

## 4. 业务规则

1. 仅 `PAID / SHIPPED / DELIVERED` 订单可发起售后。
2. `退货退款` 仅允许 `SHIPPED / DELIVERED`。
3. 退款金额始终取 `orders.total_amount_cents`，本期只支持全额退款。
4. 同一订单允许存在多条历史工单，但 `PENDING / REFUNDING` 最多一条；拒绝后可重新发起。
5. 创建售后时锁订单行，再检查进行中的工单，避免并发重复创建。
6. 状态流转使用带原状态条件的原子更新，重复或并发审核只有一个成功。
7. 退款完成不修改订单状态、不恢复库存；退货入库和真实退款属于后续范围。
8. 订单存在 `PENDING / REFUNDING / REFUNDED` 售后时禁止取消，避免退款与取消库存同时发生。

## 5. 状态流转约束

| 操作 | 允许的原状态 | 目标状态 |
|---|---|---|
| 审核通过 | `PENDING` | `REFUNDING` |
| 审核拒绝 | `PENDING` | `REJECTED` |
| 确认退款 | `REFUNDING` | `REFUNDED` |

其余状态流转均视为非法操作。

## 6. 表结构调整

需要同步修改 `infra/schema/shop.sql` 的 `aftersale_ticket`：

- `status` 注释改为：`0-待审核 1-退款中 2-已退款 3-已拒绝`。
- `status`  CHECK 约束改为 `status IN (0, 1, 2, 3)`。
- 新增 `review_remark VARCHAR(500) DEFAULT NULL`，用于审核备注。
- 新增 `reviewed_at DATETIME DEFAULT NULL`，记录审核通过或拒绝时间。
- 新增 `refunded_at DATETIME DEFAULT NULL`，记录退款完成时间。
- 索引由 `(order_no)` 调整为 `(order_no, status)`，支持按订单和状态查询。
- 不加 `order_no` 唯一索引，以支持拒绝后重新发起。

## 7. 错误语义

| HTTP 状态 | 场景 |
|---|---|
| 400 | 参数缺失、格式错误或超过长度限制 |
| 404 | 创建时订单不存在，或查询/流转时售后工单不存在 |
| 409 | 订单状态不符、售后类型不符、存在进行中售后、非法状态流转、订单禁止取消 |
| 500 | 未预期的系统异常 |

建议业务消息：

- `订单不存在`
- `售后工单不存在`
- `当前订单状态不允许发起售后`
- `当前订单状态不允许退货退款`
- `该订单已有进行中的售后工单`
- `售后状态不允许流转`
- `该订单存在售后记录，不能取消`

## 8. 并发与一致性要求

- 创建售后：按 `order_no` 锁定订单行，在锁内检查进行中工单并插入，保证并发创建不会产生多条进行中工单。
- 审核通过：`UPDATE ... WHERE id = ? AND status = 0`。
- 审核拒绝：`UPDATE ... WHERE id = ? AND status = 0`。
- 确认退款：`UPDATE ... WHERE id = ? AND status = 1`。
- 更新行数为 0 时返回 409，禁止重复提交造成多次状态变更。
- 取消订单前检查是否存在 `PENDING / REFUNDING / REFUNDED` 售后；存在则拒绝取消。

## 9. 验收清单

- 合法订单可发起售后，退款金额与订单总金额一致。
- `CREATED / CANCELLED` 订单发起售后返回 409。
- `PAID` 订单发起退货退款返回 409。
- 同一订单并发发起售后，最多产生一条进行中工单。
- 拒绝后可重新发起售后。
- 审核通过进入退款中，确认退款后进入已退款。
- 重复审核、越级确认退款、终态再次流转均返回 409。
- 并发审核同一工单只有一个成功。
- 存在进行中或已退款售后时，取消订单返回 409。
- 分页、订单号过滤、状态过滤正确。
- `mvn test` 全绿，Swagger 可查看并调用完整售后接口。
