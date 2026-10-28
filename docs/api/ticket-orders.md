# 票单 API

两个接口均使用 `Authorization: Bearer <JWT>`，要求当前账号为 `ACTIVE`。`USER`、`PUBLISHER` 均可购买。用户 ID 由 `CurrentUserProvider` 提供，不接受客户端提供的身份作为授权依据。

## 创建订单

`POST /ticket-orders` 使用 `Content-Type: application/json`。

自动配座示例：

```json
{
	"requestId": "b3b72e2d-73e9-42de-8a05-3d6df4621b6c",
	"sessionId": 1001,
	"items": [
		{
			"zoneId": 201,
			"allocationMode": "AUTO_ASSIGN",
			"quantity": 2,
			"acceptNonAdjacent": true
		}
	]
}
```

自主选座的明细示例：

```json
{
	"zoneId": 201,
	"allocationMode": "SELECT_SEAT",
	"requestedSeatId": 3001,
	"quantity": 1,
	"acceptNonAdjacent": false
}
```

| 字段 | 规则 |
|---|---|
| `requestId` | 非空白字符串，最长 64 字符；同一次创建重试必须复用 |
| `sessionId` | 正的 Long 值，一单对应一个场次 |
| `items` | 至少一项，元素非空 |
| `zoneId` | 正的 Long 值，区域属于请求场次且为 `PAID` |
| `allocationMode` | `AUTO_ASSIGN` 或 `SELECT_SEAT`，必须等于场次的 `seatMode` |
| `quantity` | 正整数；`SELECT_SEAT` 必须为 `1` |
| `requestedSeatId` | `SELECT_SEAT` 必填且属于该区域；`AUTO_ASSIGN` 必须省略或为 `null` |
| `acceptNonAdjacent` | 必填布尔值；`SELECT_SEAT` 必须为 `false` |

订单与明细均拒绝额外 JSON 字段，包括值为 `null` 的字段。客户端不能提供 `buyerId`、价格、总额、订单状态、订单 ID、明细 ID 或时间。单价取自 `session_zone.price`，总额使用 `BigDecimal` 计算，状态固定为 `PENDING_PAYMENT`，创建时间及 15 分钟过期时间由数据库生成。

创建要求活动为 `PUBLISHED`、类型为 `PAID`，场次为 `SCHEDULED` 且尚未开始，数据库当前时间位于 `[activity.open_time, activity.close_time)`，并通过当前白名单、库存和限购预检查。场次名单配置完全覆盖活动名单，显式空名单拒绝所有账号。

### 创建响应与幂等

首次创建返回 `201 Created`。相同购买者、相同 `requestId` 和相同购买意图返回 `200 OK` 及原订单；不同购买意图返回 `409 IDEMPOTENCY_CONFLICT`。比较场次及每条明细的区域、模式、指定座位、数量、相邻偏好，忽略明细顺序，保留重复行，不按总额判断相等。

两种成功响应均为 `TicketOrderResponse`：

| 响应字段 | 内容 |
|---|---|
| `orderId`、`requestId`、`sessionId` | 数据库已存订单标识和购买场次 |
| `totalAmount`、`status` | 已存总额及当前状态 |
| `createdAt`、`expiresAt`、`paidAt` | 数据库时间；未支付时 `paidAt = null` |
| `items` | 全部明细，包含 `orderItemId`、购买意图字段及 `unitPrice` 快照 |

重放不重新计价、不续期、不重置状态。当前账号仍须有效，但重放不重新执行新订单的窗口、名单、库存或限购检查。唯一键冲突后先结束失败创建事务，再开启独立事务重查和比较原单。

### 创建不预占资源

创建成功只增加一行 `ticket_order` 及全部 `ticket_order_item`。不会扣减 `session_zone.remains_amount`、改变 Seat 状态、增加或初始化有效购票计数，也不会生成 Ticket。`AUTO_ASSIGN` 不提前决定实际座位，`SELECT_SEAT` 仅记录购买意图。

当前库存和限购只是预检查，创建成功不保证支付成功。后续支付以数据库当前数据为准，重新核验资格、有效期、场次、限购、库存和座位。`requestId` 只保护创建幂等，重复支付需要单独的支付幂等机制；当前尚未提供支付接口。

## 本人订单详情

`GET /ticket-orders/{orderId}` 返回 `200 OK` 和上述 `TicketOrderResponse`。订单买家必须等于当前有效账号。活动发布者没有读取其他买家订单的例外权限。API 不定义 `buyerId` 查询参数，附带该参数也不改变身份或授权结果。

详情读取不修改订单，也不检查下单窗口、当前白名单或库存。本人可以查询过期的待支付订单及已存其他状态，实际支付条件单独校验。

## 错误响应

认证失败沿用 Security JSON。业务及请求错误使用包含 `code` 的 Problem Detail，例如：

```json
{
	"type": "about:blank",
	"title": "Conflict",
	"status": 409,
	"detail": "Request ID has already been used with a different purchase intent",
	"code": "IDEMPOTENCY_CONFLICT"
}
```

| HTTP | code | 原因 |
|---|---|---|
| `401` | `UNAUTHORIZED` | 未登录、JWT 无效或当前账号不可用 |
| `400` | `INVALID_TICKET_ORDER_REQUEST` | JSON、字段格式、必填值、枚举或正整数 ID 不合法 |
| `404` | `ACTIVITY_NOT_FOUND`、`SESSION_NOT_FOUND`、`ZONE_NOT_FOUND`、`SEAT_NOT_FOUND` | 购买资源或父资源不存在 |
| `409` | `ACTIVITY_NOT_PUBLISHED` | 活动未正式发布 |
| `409` | `ORDER_WINDOW_NOT_OPEN`、`ORDER_WINDOW_CLOSED` | 不在新订单创建窗口内 |
| `409` | `SESSION_NOT_AVAILABLE`、`SESSION_STARTED` | 场次状态不可交易或已经开始 |
| `403` | `QUALIFICATION_DENIED` | 当前名单不允许参与 |
| `400` | `INVALID_ZONE_FOR_SESSION` | Zone 不属于请求 Session |
| `400` | `INVALID_SEAT_FOR_ZONE` | Seat 不属于请求 Zone |
| `400` | `INVALID_ALLOCATION_MODE` | 明细配座模式与场次配置不一致 |
| `400` | `GIFT_ZONE_NOT_PURCHASABLE` | 赠票区域不能公开购买 |
| `409` | `INSUFFICIENT_CURRENT_INVENTORY` | 当前 Zone 余量预检查不足 |
| `409` | `PURCHASE_LIMIT_PRECHECK_FAILED` | 当前有效票数加本单合计数量超过限购 |
| `409` | `IDEMPOTENCY_CONFLICT` | 同一创建幂等键对应不同购买意图 |
| `404` | `ORDER_NOT_FOUND` | 指定订单不存在 |
| `403` | `ORDER_ACCESS_DENIED` | 当前账号不是订单买家 |
| `400` | `ACTIVITY_NOT_PAID`、`INVALID_ORDER_ITEM`、`DUPLICATE_REQUESTED_SEAT`、`ORDER_AMOUNT_EXCEEDED` | 活动类型、明细、重复指定座位或金额不合法 |
| `409` | `INVALID_SESSION_CONFIG`、`INVALID_ZONE_PRICE`、`ORDER_CREATION_CONFLICT` | 付费配置或写入条件不合法 |

HTTP 状态与 `code` 都是契约的一部分。格式非法与业务条件拒绝分别处理，不统一返回 `BAD_REQUEST`。

## 自动化验收

[创建与本人详情 MySQL 测试](../../src/test/java/com/limitactivitysystem/activity/integration/TicketOrderCreationMySqlIntegrationTest.java)覆盖正常模式、认证、生命周期、窗口、名单、父子关系、错误码、事务故障、资源不预占及跨用户查询。[幂等并发测试](../../src/test/java/com/limitactivitysystem/activity/integration/TicketOrderIdempotencyConcurrencyMySqlIntegrationTest.java)通过 barrier 验证唯一键竞争、回滚后重放及不同载荷冲突。

项目根目录中，在已配置 MySQL 连接和 `DB_PASSWORD` 的进程中执行：

```powershell
mvn '-Dtest=TicketOrderCreationMySqlIntegrationTest,TicketOrderIdempotencyConcurrencyMySqlIntegrationTest' test
```

两种选座模式的不预占前后快照写入 `target/verification/ticket-order-no-reservation-AUTO_ASSIGN.json` 和 `target/verification/ticket-order-no-reservation-SELECT_SEAT.json`。快照与测试结果须来自同一次运行，详细证据见 [票单验收](../ticket-orders.md#下单不预占的数据库证据)。
