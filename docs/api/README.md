# API 索引

最终契约为 [OpenAPI 3.0.3](openapi.yaml)，采用 JSON 语法保存（JSON 也是合法 YAML）。契约由生产 Controller 和 DTO 生成，`ReleaseApiContractTest` 校验代码与契约一致。导入 [Postman 2.1 请求集合](../../tests/acceptance/apifox-collection.json) 可在 Apifox 中按流程执行；账号准备、环境变量和异步等待说明见 [演示手册](../demo.md)。集合覆盖完整演示流程，OpenAPI 覆盖全部生产业务端点。

认证使用 `Authorization: Bearer <accessToken>`。用户注册默认为 USER；演示脚本只对本轮新建账号配置 PUBLISHER/ADMIN。发布者、买家身份由服务端 JWT 和当前账号状态决定。业务拒绝的 ProblemDetail 与 Security 过滤链错误格式可能不同，以响应状态和各 API 文档为准。

| 接口 | 用途 | 文档 |
|---|---|---|
| `POST /ticket-orders` | 创建待支付票单，按当前购买者和请求 ID 幂等 | [票单 API](ticket-orders.md) |
| `GET /ticket-orders/{orderId}` | 查询本人订单及全部明细 | [票单 API](ticket-orders.md#本人订单详情) |
| `POST /ticket-orders/{orderId}/pay` | 模拟支付，重复请求返回原 Ticket | [支付 API](ticket-payments.md) |
| `POST /ticket-refunds` | 受理本人整单或逐票退款，保存政策与金额快照，返回 PENDING | [退款申请 API](ticket-refunds.md) |
| `POST /sessions/{sessionId}/reservations` | 创建本人免费预约，立即 ACTIVE 并占一份名额 | [免费预约 API](reservations.md) |
| `POST /reservations/{id}/cancel` | 开始前取消本人 ACTIVE 预约，只返还一次名额 | [免费预约 API](reservations.md#取消预约) |
| `POST /publisher/sessions/{sessionId}/reschedules` | 发布改期，固定受影响快照并保存独立历史 | [场次改期 API](session-reschedules.md) |
| `GET /reschedules`、`GET /reschedules/{eventId}` | 查询本人受影响历史、响应和特殊退款 | [场次改期 API](session-reschedules.md) |
| `POST /reschedules/{eventId}/confirm` | 主动保留，保留窗口内特殊退款权利 | [场次改期 API](session-reschedules.md) |
| `POST /reschedules/{eventId}/special-refunds` | 按事件资格整批受理零手续费退款 | [场次改期 API](session-reschedules.md) |
| `POST /publisher/sessions/{id}/cancel`、`POST /publisher/activities/{id}/cancel` | 短事务取消生效，异步分项处理 | [取消 API](cancellations.md) |
| `GET /publisher/cancellations/{id}`、`POST /publisher/cancellations/{id}/items/{item}/retry` | 批次明细分页查询、失败恢复 | [取消 API](cancellations.md#查询与恢复) |
| `GET /sessions/{id}/cancellation`、`GET /activities/{id}/cancellation` | 公开取消原因及批次状态 | [取消 API](cancellations.md#公开取消信息与参与效力) |

创建事务、并发实验和测试证据见 [票单实现与验收](../ticket-orders.md)。
