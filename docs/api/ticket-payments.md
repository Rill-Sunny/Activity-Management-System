# 模拟门票支付 API

## 请求与身份

`POST /ticket-orders/{orderId}/pay` 使用 `Authorization: Bearer <JWT>`。当前账号必须为 `ACTIVE` 且拥有订单。`USER` 和 `PUBLISHER` 都可支付自己的订单。

请求无需正文，也接受空 JSON 对象 `{}`。任何非空对象均返回 `400 INVALID_PAYMENT_REQUEST`。客户端不能提供 `buyerId`、`totalAmount`、`paidAt`、`status`、`ticketId` 或 `seatId`，也不能在支付时改变原订单的数量或非连座偏好。

```http
POST /ticket-orders/100/pay
Authorization: Bearer <JWT>
```

## 成功结果与重放

首次支付成功与已支付订单的重复请求都返回 `200`。响应中的 Ticket ID、Seat、价格和时间来自数据库：

```json
{
	"orderId": 100,
	"sessionId": 1001,
	"totalAmount": 29.90,
	"status": "PAID",
	"paidAt": "2026-10-26T20:00:00.123",
	"tickets": [
		{
			"ticketId": 501,
			"orderItemId": 201,
			"seatId": 3001,
			"paidPrice": 29.90,
			"status": "VALID",
			"issuedAt": "2026-10-26T20:00:00.120"
		}
	]
}
```

每个实际 Seat 对应一张 `VALID` Ticket，数量逐明细等于 `ticket_order_item.quantity`，成交单价来自该明细已存的 `unit_price`。响应总额沿用创建时的服务端快照，不按支付时 Zone 展示价格重算。

重复支付先锁订单并检查当前账号与买家。已经 `PAID` 时直接读取原 Ticket，不再次占用额度、扣库存或签票，也不重新应用后来变化的白名单、停售时间或订单期限。`CLOSED`、`PARTIALLY_REFUNDED`、`REFUNDED` 不能再次支付。

## 待支付订单的条件

支付使用数据库时间，要求 `created_at <= now < expires_at`，场次仍为 `SCHEDULED` 且 `now < start_time`，活动仍为 `PUBLISHED`、`PAID`。锁等待结束后重新读取当前时间和状态，最终 PAID 条件更新再次保护期限。

活动停售时间只阻止创建新单。停售前创建的订单在自身 15 分钟期限内仍可尝试支付。待支付订单重新检查当前名单；下单时通过资格不构成支付授权。

订单、限购计数、Zone 余量、Seat 与 Ticket 在同一个事务提交。任一步失败都会回滚本次资源写入。

## 错误与缺货关闭

错误使用项目既有 JSON 错误格式，包含稳定的 `code`。完整错误表见[支付失败原因](../payment-resources.md#支付失败原因)。

| 场景 | HTTP / code | 订单处理 |
|---|---|---|
| 未认证、非法 Token、认证时账号已禁用 | `401 UNAUTHORIZED` | 不支付 |
| 订单不存在 | `404 ORDER_NOT_FOUND` | 不写入 |
| 其他买家的订单 | `403 ORDER_ACCESS_DENIED` | 不写入 |
| 当前名单不通过 | `403 QUALIFICATION_DENIED` | 保留待支付状态 |
| 非待支付且非 PAID 状态 | `409 ORDER_NOT_PENDING` | 保留原状态 |
| 订单过期或场次不可交易 | `409 ORDER_EXPIRED` / `SESSION_NOT_AVAILABLE` | 不自动关闭 |
| 限购不足 | `409 PURCHASE_LIMIT_EXCEEDED` | 保留待支付状态 |
| 指定座位冲突、无所需连座 | `409 SEAT_NOT_AVAILABLE` / `ADJACENT_SEATS_UNAVAILABLE` | 保留待支付状态 |
| Zone 库存不足 | `409 INSUFFICIENT_INVENTORY` | 支付完全回滚后，另开短事务关闭仍为 PENDING_PAYMENT 的原单 |
| 最终状态条件更新未生效 | `409 ORDER_PAYMENT_CONFLICT` | 全部回滚 |
| 死锁或锁等待超时连续三次失败 | `503 PAYMENT_CONCURRENCY_RETRY_EXHAUSTED` | 每次完整回滚，保留最后读取到的订单状态 |

缺货关闭记录 `closed_reason = INSUFFICIENT_INVENTORY`。关闭事务重新锁定订单：如果另一请求已经支付成功，返回其原支付结果，不覆盖 PAID；如果已经进入其他状态，保留该状态。

数据库断连、SQL 错误及未知异常继续作为技术失败向外传播，不转换为缺货，不执行缺货关闭。关闭事务自身失败也会向外传播，不能声称已安全关闭。

支付只对明确的 MySQL 并发错误重试：死锁 `1213 / 40001`，以及锁等待超时 `1205 / HY000` 或连接器返回的 `1205 / 40001`。最多三次总尝试，重试前分别等待 50–75ms、100–125ms。每次失败事务结束后，下一次尝试重新锁订单、复查资格、占限购、扣库存、分座并出票。业务拒绝、断连、SQL 语法错误与未知异常不重试。

重试耗尽响应包含 `code`、`reason`（`DEADLOCK` 或 `LOCK_WAIT_TIMEOUT`）和 `attempts: 3`，不包含 SQL 或异常栈。主动取消与数据库分批过期扫描已接入；支付本身的过期拒绝不执行关闭，扫描独立关闭到期的待支付订单。锁与事务设计见 [locking.md](../locking.md#ticket-payment-resources)，实验证据见[支付重试验证](../../qa/reports/payment-retry-verification.md)。

支付尝试产生 `payment.success`、按固定原因分类的 `payment.failure` 和 `payment.duration`。已认证管理端点可读取这些指标。它们统计 Service 请求结果，包含重复支付成功读取；不等于新增成交订单数。日志字段及自动对账范围见[支付对账与可观测性](../../qa/reports/payment-verification.md)。
