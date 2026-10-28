# API 索引

| 接口 | 用途 | 文档 |
|---|---|---|
| `POST /ticket-orders` | 创建待支付票单，按当前购买者和请求 ID 幂等 | [票单 API](ticket-orders.md) |
| `GET /ticket-orders/{orderId}` | 查询本人订单及全部明细 | [票单 API](ticket-orders.md#本人订单详情) |
| `POST /ticket-orders/{orderId}/pay` | 模拟支付，重复请求返回原 Ticket | [支付 API](ticket-payments.md) |

创建事务、并发实验和测试证据见 [票单实现与验收](../ticket-orders.md)。
