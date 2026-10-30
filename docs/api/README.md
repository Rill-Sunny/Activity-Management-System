# API 索引

| 接口 | 用途 | 文档 |
|---|---|---|
| `POST /ticket-orders` | 创建待支付票单，按当前购买者和请求 ID 幂等 | [票单 API](ticket-orders.md) |
| `GET /ticket-orders/{orderId}` | 查询本人订单及全部明细 | [票单 API](ticket-orders.md#本人订单详情) |
| `POST /ticket-orders/{orderId}/pay` | 模拟支付，重复请求返回原 Ticket | [支付 API](ticket-payments.md) |
| `POST /ticket-refunds` | 受理本人整单或逐票退款，保存政策与金额快照，返回 PENDING | [退款申请 API](ticket-refunds.md) |
| `POST /sessions/{sessionId}/reservations` | 创建本人免费预约，立即 ACTIVE 并占一份名额 | [免费预约 API](reservations.md) |
| `POST /reservations/{id}/cancel` | 开始前取消本人 ACTIVE 预约，只返还一次名额 | [免费预约 API](reservations.md#取消预约) |

创建事务、并发实验和测试证据见 [票单实现与验收](../ticket-orders.md)。
