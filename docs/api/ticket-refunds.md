# 普通门票退款申请 API

`POST /ticket-refunds` 要求 `Authorization: Bearer <JWT>`。USER 和 PUBLISHER 都只能为自己的已支付门票申请退款。

## 提交申请

正文只接受以下字段：

```json
{
	"requestId": "refund-20261029-001",
	"orderId": 10001,
	"ticketIds": [50001, 50003],
	"reason": "USER_REQUEST"
}
```

requestId 为非空字符串，最多 64 字符；reason 为非空字符串，最多 500 字符。orderId、ticketIds 的元素均为正整数，票集合非空且不允许重复。全部门票和指定子集使用同一入口。

buyerId、refundRate、feeRate、refundAmount、originalPrice、requestedAt、status 以及其他额外字段均拒绝，包括值为 null 的情况。用户身份来自 CurrentUserProvider，申请时间来自服务器注入的 Clock，金额来自 Ticket 的实际成交价及服务端政策。

成功返回 `201 Created`，例如：

```json
{
	"refundId": 101,
	"buyerId": 7,
	"orderId": 10001,
	"requestId": "refund-20261029-001",
	"reason": "USER_REQUEST",
	"requestedAt": "2026-10-29T11:00:00.123",
	"status": "PENDING",
	"createdAt": "2026-10-29T11:00:00.130",
	"items": [
		{
			"refundItemId": 201,
			"refundId": 101,
			"ticketId": 50001,
			"originalPrice": 10.01,
			"feeRate": 0.1500,
			"refundAmount": 8.51,
			"status": "PENDING",
			"failureReason": null
		},
		{
			"refundItemId": 202,
			"refundId": 101,
			"ticketId": 50003,
			"originalPrice": 20.03,
			"feeRate": 0.1500,
			"refundAmount": 17.03,
			"status": "PENDING",
			"failureReason": null
		}
	]
}
```

该响应表示申请已受理。主申请与逐票明细均为 PENDING。Ticket 仍为 VALID，订单、座位、库存和限购计数保持原值，实际生效属于 Day 11。

## 所有权、政策与幂等

服务锁定并验证 order.buyer_id 等于当前账号，再对每个 ticketId 检查 `Ticket.order_item_id → TicketOrderItem.order_id → TicketOrder.buyer_id`。同一用户另一订单的票也不能混入。每张票必须为 VALID，整单状态须为 PAID 或 PARTIALLY_REFUNDED；任一票失败则整笔申请不写入。

requested_at 在认证通过后、等待订单锁之前读取一次服务器 Clock，按 Asia/Shanghai 时区转为本地时间，截断到毫秒后保存。手续费按活动 open_time、场次 start_time 与该申请时间计算，逐票使用 BigDecimal、两位 HALF_UP。政策版本及开售、开始时间快照保存在主申请，逐票的原价、费率、应退金额直接持久化。处理或重放不重新计算政策。

相同账号、相同 requestId、相同 orderId、reason、票集合返回原申请，仍返回 201。票 ID 输入顺序不影响幂等。更换内容返回 409 REFUND_IDEMPOTENCY_CONFLICT。不同 requestId 也不能对已有 PENDING、PROCESSING 或 SUCCEEDED 申请的同一票重复受理。

## 错误

| 情况 | HTTP / code |
|---|---|
| 未登录、无效 JWT、认证时账号已禁用 | 401 UNAUTHORIZED |
| 非法参数、重复票 ID、客户端提交服务端字段 | 400 INVALID_REFUND_REQUEST |
| 订单不存在 | 404 REFUND_ORDER_NOT_FOUND |
| 订单不属于当前账号 | 403 REFUND_ACCESS_DENIED |
| 事务内发现账号已禁用 | 403 REFUND_USER_DISABLED |
| 订单不是已支付或部分退款状态 | 409 REFUND_ORDER_NOT_PAID |
| Ticket 或关联订单明细不存在 | 404 REFUND_TICKET_NOT_FOUND |
| Ticket 不属于指定订单及买家 | 409 REFUND_TICKET_NOT_IN_ORDER |
| Ticket 不是 VALID | 409 REFUND_TICKET_NOT_VALID |
| 同票已有受理中或成功的退款 | 409 REFUND_ALREADY_REQUESTED |
| requestId 已用于不同退款意图 | 409 REFUND_IDEMPOTENCY_CONFLICT |
| 普通退款活动或场次作用域不可用 | 409 REFUND_SESSION_UNAVAILABLE |
| 申请时场次已开始 | 409 REFUND_SESSION_STARTED |
| 不符合普通受理条件，例如申请早于 Ticket 签发 | 409 REFUND_NOT_ALLOWED |
| 已过免费窗口且距离开始至多十天 | 409 REFUND_WINDOW_CLOSED |
| 服务端成交价无法产生合法退款金额 | 500 REFUND_AMOUNT_CALCULATION_ERROR |

退款窗口关闭、场次已开始及一般不允许受理分别返回不同 code。金额计算错误不转换成客户端参数错误。相同 requestId、相同载荷返回原申请；DUPLICATE_REFUND_REQUEST 对应的拒绝仅指不同请求争用同一张票，实际 code 为 REFUND_ALREADY_REQUESTED。

指定 200、400、800、1200 四票价，申请 B + D 的逐票与汇总结果见 [四票价申请实验](../ticket-refunds.md#四票价申请实验)。主表不保存 total_refund，汇总取持久化明细之和。

技术异常退出事务并完整回滚，不创建半张退款单。领域、政策和测试入口见 [Day 10 普通门票退款申请契约](../ticket-refunds.md)。
