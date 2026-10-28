# 票单 API 契约

`POST /ticket-orders` 已接通真实创建 Controller、Service 和 Mapper。首次创建在同一事务中校验当前用户、生命周期、资格、明细归属、库存与限购预检查，保存待支付订单及价格快照。重复请求使用方案 A 比较原订单与明细，返回原单或明确的载荷冲突。`GET /ticket-orders/{orderId}` 返回当前购买者本人的订单与明细。完整模拟支付见[支付 API](api/ticket-payments.md)。

客户端接口参考见 [docs/api 票单 API](api/ticket-orders.md)，本页同时记录实现边界和验收证据。

## 接口与身份

创建接口为 `POST /ticket-orders`，使用 `Content-Type: application/json`。详情接口为 `GET /ticket-orders/{orderId}`。两者均通过 `Authorization: Bearer <JWT>` 认证，`USER`、`PUBLISHER` 均可作为消费者。购买者只从 `CurrentUserProvider` 取得，账号必须为 `ACTIVE`，不从请求体、查询参数或客户端价格中推断。

## 本人订单详情

`GET /ticket-orders/{orderId}` 由 `TicketOrderQueryService.getOwnOrder` 在只读事务中执行。Service 从 `CurrentUserProvider` 取得当前有效用户 ID，读取订单后要求 `order.buyer_id == currentUserId`，通过后才查询明细。响应为 `200 OK` 和现有 `TicketOrderResponse`，包含已存订单状态、金额、时间与全部明细价格快照。

API 不定义 `buyerId` 查询参数。即使请求附带它，也不会改变当前身份或授权结果：本人附带其他买家 ID 仍读取自己的订单，他人附带订单买家 ID 仍被拒绝。活动发布者同样只能查询自己购买的订单，拥有该活动不能代替订单买家身份。

| HTTP | code | 条件 |
|---|---|---|
| `401` | `UNAUTHORIZED` | 未认证、令牌无效或当前账号已禁用 |
| `403` | `ORDER_ACCESS_DENIED` | 当前账号不是订单买家，响应不包含订单明细 |
| `404` | `ORDER_NOT_FOUND` | 指定订单不存在 |
| `400` | `INVALID_TICKET_ORDER_REQUEST` | `orderId` 不是正的 Long 值 |

详情读取不产生新的参与行为，不检查下单窗口、当前名单、库存或限购，也不延长有效期或改变订单状态。过期的待支付订单、已支付或已关闭订单仍可由本人读取。支付权限与资格由后续支付事务单独判断。

`TicketOrderCreationMySqlIntegrationTest` 使用真实 JWT、Security 过滤链和 MySQL 验证本人查询、普通用户及发布者越权、伪造 `buyerId`、匿名和禁用账号、缺失与非法 ID、状态及明细原样读取，以及窗口和名单变化后的可见性。直接调用 Service 的用例也验证归属校验，避免将权限判断仅放在 Controller。

## 创建请求字段

请求 DTO 为 `CreateTicketOrderRequest`、`CreateTicketOrderItemRequest`，响应 DTO 为 `TicketOrderResponse`、`TicketOrderItemResponse`。请求 DTO 不查询数据库。`TicketOrderService` 协调幂等重放与首次创建，资源归属、可下单状态与白名单资格由 `TicketOrderCreationService` 校验。

订单层只允许以下字段：

| 字段 | 类型 | 规则 |
|---|---|---|
| `requestId` | String | 必填、非空白、最长 64 个字符，与 `ticket_order.request_id` 一致，不强制 UUID 格式 |
| `sessionId` | Long | 必填、正整数，一单对应一个场次 |
| `items` | Array | 必填、至少一条明细，元素不能为 `null` |

每条明细只允许以下字段：

| 字段 | 类型 | 规则 |
|---|---|---|
| `zoneId` | Long | 必填、正整数 |
| `allocationMode` | String | 必填，仅 `SELECT_SEAT`、`AUTO_ASSIGN`，区分大小写 |
| `requestedSeatId` | Long | 自主选座必填且为正整数，自动配座省略或为 `null` |
| `quantity` | Integer | 必填且大于零，自主选座必须等于 `1` |
| `acceptNonAdjacent` | Boolean | 必填，自主选座必须为 `false`，自动配座保留用户的明确选择 |

自动配座请求示例：

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

自主选座请求示例：

```json
{
	"requestId": "9191de62-7638-4b30-afaf-90e5e02f834a",
	"sessionId": 1001,
	"items": [
		{
			"zoneId": 201,
			"allocationMode": "SELECT_SEAT",
			"requestedSeatId": 3001,
			"quantity": 1,
			"acceptNonAdjacent": false
		}
	]
}
```

订单与明细均拒绝白名单以外的 JSON 字段，即使字段值为 `null`。因此 `buyerId`、`unitPrice`、`totalAmount`、`status`、`createdAt`、`expiresAt`、`paidAt` 不能在任一层提交。订单 ID、明细 ID 也不能由客户端指定。无效 JSON、必填字段缺失、数量或模式组合不合法统一返回 `400 INVALID_TICKET_ORDER_REQUEST`，使用 Problem Detail。

## 服务端字段来源

| 字段 | 唯一可信来源 |
|---|---|
| `buyer_id` | `CurrentUserProvider` 的当前有效用户 ID |
| 明细 `unit_price` | 已校验归属的 `session_zone.price`，作为创建时价格快照 |
| `total_amount` | Service 使用 `BigDecimal` 计算全部明细的 `unit_price × quantity` 之和 |
| `status` | Service 固定为 `PENDING_PAYMENT` |
| `created_at` | 数据库 `DEFAULT CURRENT_TIMESTAMP(3)`，插入时省略该列 |
| `expires_at` | 数据库存储生成列 `DATE_ADD(created_at, INTERVAL 15 MINUTE)`，插入时省略该列 |
| `paid_at` | 创建时为 `NULL`，后续支付事务设置 |
| `order_id`、`order_item_id` | 数据库自增主键 |

创建后重新读取数据库记录返回时间，不能用 Java 当前时间代替数据库创建时间，也不能在应用中另算过期时间后写入生成列。[V1 迁移](../src/main/resources/db/migration/V1__create_schema.sql)已经定义这些列，本步不修改 V1 或 V3。

## 成功响应契约

首次创建成功返回 `201 Created`，相同购买意图的重复请求返回 `200 OK`，均使用 `TicketOrderResponse`，其 `items` 使用 `TicketOrderItemResponse`。以下响应仅为格式示例，价格和时间由服务端与数据库产生：

```json
{
	"orderId": 5001,
	"requestId": "b3b72e2d-73e9-42de-8a05-3d6df4621b6c",
	"sessionId": 1001,
	"totalAmount": 200.00,
	"status": "PENDING_PAYMENT",
	"createdAt": "2026-10-24T20:00:00.000",
	"expiresAt": "2026-10-24T20:15:00.000",
	"paidAt": null,
	"items": [
		{
			"orderItemId": 6001,
			"zoneId": 201,
			"allocationMode": "AUTO_ASSIGN",
			"requestedSeatId": null,
			"quantity": 2,
			"acceptNonAdjacent": true,
			"unitPrice": 100.00
		}
	]
}
```

## 创建事务与业务校验

`TicketOrderCreationService.createOrder` 使用独立的 `REQUIRES_NEW`、`READ_COMMITTED` 事务，执行以下检查后才写入订单：

1. 从 `CurrentUserProvider` 取得当前有效用户，确认 Session 及所属 Activity 存在。
2. 要求 Activity 为 `PUBLISHED`、类型为 `PAID`，Session 为 `SCHEDULED` 且付费配置有效。
3. 调用 `QualificationService.check`，在当前事务中按场次覆盖、活动继承、公开规则判断资格。显式空名单拒绝所有账号。
4. 在资格共享锁下重新读取 Activity、Session，复核生命周期与父子关系，再用数据库时间检查创建窗口和开始时间。
5. 逐项核对 Zone 属于请求 Session、类型为 `PAID`，并要求明细模式等于场次 `seat_mode`。
6. 自主选座要求一个指定 Seat、`quantity = 1`、`acceptNonAdjacent = false`，Seat 必须存在且属于该 Zone。同单不能重复指定同一 Seat。
7. 自动配座要求 `requestedSeatId = null`、正整数数量，并保留用户的 `acceptNonAdjacent`。创建时不查询候选座位或分配实际座位。
8. 从数据库 Zone 价格生成明细快照，用 `BigDecimal` 计算总额，完成库存与限购预检查后保存主表和全部明细。

资格失败返回 `403 QUALIFICATION_DENIED`，不写入任何订单或明细。任何后续写入失败也会回滚整个创建事务。

### 创建窗口与支付窗口

创建必须满足数据库时间位于 `[activity.open_time, activity.close_time)`，同时严格早于 `session.start_time`。`DatabaseTimeMapper.currentTime` 每次实际执行 `SELECT CURRENT_TIMESTAMP(3)`，不使用应用服务器的当前时间或 MyBatis 缓存值。

Service 在资格锁定后检查时间，并在写入前再次检查。`insertDuringCreationWindow` 使用已锁定资源的窗口参数，在同一条 INSERT 中再次用数据库时间判断，避免检查期间跨过截止或开始时刻。插入成功时，数据库生成 `created_at` 与 `expires_at`。

`requireCreationTime` 仅用于创建新订单。支付旧订单的规则另行实现，届时检查订单自身的过期时间、场次状态与当前资格，不要求仍在新订单的创建窗口内。

### 库存与限购只是预检查

同一区域的所有明细先合计数量，再与该 Zone 的 `remains_amount` 比较。限购检查使用当前 `user_session_purchase.valid_count` 加上本单全部明细数量，与场次 `max_tickets_per_user` 比较。计数行不存在时按零处理，不创建计数行，待支付订单不计入有效购票数。

这些读取不扣库存、不占座、不修改购票计数，也不生成 Ticket。自主选座不以 Seat 当前为 `AVAILABLE` 作为创建前提，更不会将它改为 `SOLD`。资格共享锁用于协调名单和生命周期，不授予座位或库存所有权。

因此同一份当前余量可以支持多个待支付订单，创建成功不保证未来支付成功。支付阶段必须重新检查资格、限购、可售库存及座位并原子取得资源。

### 下单不预占的数据库证据

`TicketOrderCreationMySqlIntegrationTest.successfulCreationRecordsOneHundredAvailableSeatsAndZeroUsedQuotaWithoutReservingEither` 对 `AUTO_ASSIGN`、`SELECT_SEAT` 分别通过真实 HTTP、JWT、Service 事务及 MySQL 创建包含两条明细的订单。使用已正式发布的独立活动、100 个配置完整的座位，以及已存在且有效购票数为零的计数行。

| 数据库事实 | Before | Create Order 后 |
|---|---:|---:|
| 目标 Zone `remains_amount` | 100 | 100 |
| 目标 Zone 的 `AVAILABLE` Seat | 100 | 100 |
| 目标 Zone 的非 `AVAILABLE` Seat | 0 | 0 |
| 当前买家的场次购票计数行数 | 1 | 1 |
| 当前买家的 `valid_count` | 0 | 0 |
| `ticket` 行数 | 0 | 0 |
| `ticket_order` 行数 | 0 | 1 |
| `ticket_order_item` 行数 | 0 | 2 |

测试还比较 `session_zone`、`session_seat`、`user_session_purchase`、`ticket` 的完整行快照，验证其 ID、状态和其他字段均未改写。现有 `pendingOrdersNeitherReserveStockNorConsumePurchaseQuota` 另行验证计数行不存在时保持不存在，以及两张待支付订单可以使用相同当前余量进行预检查。

每个模式的测试通过后，将实际观察结果、模式、测试名和数据库观察时间写入：

- `target/verification/ticket-order-no-reservation-AUTO_ASSIGN.json`
- `target/verification/ticket-order-no-reservation-SELECT_SEAT.json`

测试使用自己的随机 schema，结束后清理；快照文件用于保留本次观察结果。重新生成命令为：

```powershell
mvn '-Dtest=TicketOrderCreationMySqlIntegrationTest' test
```

运行进程须配置真实 MySQL 连接和 `DB_PASSWORD`。创建与支付的边界见 [ADR-004](decisions.md#adr-004下单不预占与支付时数据库权威)。

### 创建请求幂等：方案 A

同一次创建重试复用 `requestId`，唯一范围为 `(buyer_id, request_id)`，购买者仍从可信身份上下文取得。重复键对应相同购买意图时返回原订单；对应不同购买意图时返回 `409 IDEMPOTENCY_CONFLICT`。不同购买者可以独立使用相同请求 ID。

比较字段为主表的 `sessionId`，以及每条明细的 `zoneId`、`allocationMode`、`requestedSeatId`、`quantity`、`acceptNonAdjacent`。`TicketOrderIntent` 将双方明细按这些字段排序后逐项比较，因此仅调整输入顺序仍为同一请求。重复明细保留出现次数，不合并拆分的购买行；一行购买两张与两行各买一张不是相同载荷，自动配座的相邻偏好也按行保留。

价格、总额、数据库 ID、状态与时间均不是客户端载荷，不参与意图比较。重放读取原订单当前已存状态和原价格快照，保留创建时间、过期时间、已支付时间与明细 ID，不重新计价、不延长有效期、不将已支付或已关闭订单重置为待支付。相同金额也可能来自不同区域和数量，不能据此判断请求相同。

重放先于新订单的销售窗口、名单、库存和限购校验。它只读取既有订单，不产生新的参与行为；账号仍须为 `ACTIVE`。即使窗口已关闭、名单已改变或订单已过期，也返回匹配的原单。实际支付仍须另行复查当前资格和交易条件。

`TicketOrderService.createOrder` 使用 `NOT_SUPPORTED` 进行协调。初次重放查询和首次创建分别经独立 Spring Bean 的事务代理执行：`TicketOrderReplayService` 使用只读 `REQUIRES_NEW`、`READ_COMMITTED`，创建 Service 使用可写 `REQUIRES_NEW`、`READ_COMMITTED`。如果调用方已有事务，该事务会被挂起；订单创建独立提交，调用方之后回滚不会撤销这张订单。因此该入口是完整创建操作，不能作为调用方更大事务中的可回滚子步骤。

并发时，两个请求可能同时查不到订单。数据库唯一键决定唯一创建者；另一个请求在写入事务完整回滚、释放锁后，开启新的只读事务查询已提交订单，再执行同样的载荷比较。不能在已失败或带有旧快照的事务里吞掉重复键继续读写。如果迟到请求先遇到创建专用的业务或资格错误，也在回滚后重查一次，避免并发创建者已经提交却返回错误的重试结果；没有原单时继续抛出原错误。技术故障不作为幂等成功处理。

本方案复用已有主表、明细和唯一键，不新增 `request_fingerprint` 或修改 Schema。决策理由见 [ADR-003](decisions.md#adr-003创建票单按原明细实现请求幂等)。

### 业务错误

| HTTP | code | 原因 |
|---|---|---|
| `401` | `UNAUTHORIZED` | 未认证、令牌无效或账号已禁用 |
| `403` | `QUALIFICATION_DENIED` | 当前适用名单不允许参与 |
| `404` | `SESSION_NOT_FOUND`、`ACTIVITY_NOT_FOUND`、`ZONE_NOT_FOUND`、`SEAT_NOT_FOUND` | 指定资源或父资源不存在 |
| `409` | `ACTIVITY_NOT_PUBLISHED`、`SESSION_NOT_AVAILABLE`、`SESSION_STARTED` | 生命周期不允许创建 |
| `409` | `ORDER_WINDOW_NOT_OPEN`、`ORDER_WINDOW_CLOSED` | 不在新订单创建窗口内 |
| `400` | `ACTIVITY_NOT_PAID`、`GIFT_ZONE_NOT_PURCHASABLE` | 付费票单不接受免费活动或赠票区域 |
| `400` | `INVALID_ZONE_FOR_SESSION`、`INVALID_SEAT_FOR_ZONE` | 跨父子资源混写 |
| `400` | `INVALID_ALLOCATION_MODE`、`INVALID_ORDER_ITEM`、`DUPLICATE_REQUESTED_SEAT` | 选座模式或明细不合法 |
| `409` | `INSUFFICIENT_CURRENT_INVENTORY`、`PURCHASE_LIMIT_PRECHECK_FAILED` | 当前库存或限购预检查失败 |
| `409` | `INVALID_SESSION_CONFIG`、`INVALID_ZONE_PRICE` | 数据库中的付费配置不合法 |
| `400` | `ORDER_AMOUNT_EXCEEDED` | 总金额超过订单列支持范围 |
| `409` | `IDEMPOTENCY_CONFLICT` | 同一购买者的请求 ID 已用于不同购买意图 |
| `409` | `ORDER_CREATION_CONFLICT` | 订单写入未满足条件 |

业务错误使用含 `code` 的 Problem Detail，认证错误沿用统一 Security JSON。

## 最小持久层

订单实体为 `TicketOrder`，明细实体为 `TicketOrderItem`。Mapper 使用 MyBatis 注解、绑定参数和显式字段映射，不将 DTO 直接绑定为数据库实体。

| Mapper | 当前方法 |
|---|---|
| `TicketOrderMapper` | `insert`、`insertDuringCreationWindow`、`findById`、`findByBuyerIdAndRequestId` |
| `TicketOrderItemMapper` | `insert`、`batchInsert`、`findByOrderId` |
| `DatabaseTimeMapper` | `currentTime`，读取数据库时间 |
| `UserSessionPurchaseMapper` | `findValidCount`，只读取当前有效购票计数 |
| `ActivityMapper`、`ActivitySessionMapper`、`SessionZoneMapper`、`SessionSeatMapper` | 复用已有 `findById` 查询 |
| `AppUserMapper`、白名单 Mapper、`QualificationService` | 复用已有身份与资格入口 |

订单插入固定写入 `PENDING_PAYMENT`，省略 `created_at`、`expires_at`、`paid_at`、`closed_reason`，仅回填自增 `orderId`。调用方通过 `findById` 读取数据库时间。明细单条或批量插入回填各自的 `orderItemId`，查询按明细 ID 升序返回。

`findByBuyerIdAndRequestId` 同时匹配购买者和请求 ID，禁用缓存并清空 MyBatis 本地缓存后执行查询。Service 加载原明细比较完整购买意图，并负责重复键冲突后的事务回滚与重查。唯一键本身只防止重复插入，不能代替载荷比较。

## 契约测试

[TicketOrderRequestContractTest](../src/test/java/com/limitactivitysystem/ticketorder/controller/TicketOrderRequestContractTest.java)使用生产 Controller 与真实 Security 过滤链，验证两种消费者角色、两层额外字段拒绝及 Bean Validation。业务 Service 在该测试中被替换，合法输入返回 `201`。

持久化与 DataSource 在该契约测试中被替换，不需要真实 MySQL。该测试不验证价格计算、数据库生成时间或创建业务事务，Mapper 的真实数据库验证见下文。项目根目录中用 Maven 执行：

```powershell
mvn '-Dtest=TicketOrderRequestContractTest' test
```

[TicketOrderMapperMySqlIntegrationTest](../src/test/java/com/limitactivitysystem/activity/integration/TicketOrderMapperMySqlIntegrationTest.java)使用真实 MySQL 和独立随机 schema，验证生成 ID、金额与显式字段映射、数据库生成时间、购买者作用域唯一键、明细查询及事务回滚，同时检查库存、座位、有效购票计数与 Ticket 未被改变。测试读取 V1、V3，跳过带固定 `USE` 的 V2 seed，不迁移现有业务库。

在已配置 MySQL 连接和 `DB_PASSWORD` 的进程中执行 Mapper 集成测试：

```powershell
mvn '-Dtest=TicketOrderMapperMySqlIntegrationTest' test
```

## 创建业务验收

[TicketOrderCreationMySqlIntegrationTest](../src/test/java/com/limitactivitysystem/activity/integration/TicketOrderCreationMySqlIntegrationTest.java)通过真实 JWT、Controller、Service、QualificationService、MyBatis 与 MySQL 验证创建链路。测试覆盖生命周期、资格组合、父子归属、两种选座、价格快照与金额、库存和限购合计、无资源占用、旧令牌禁用及写入明细后的整笔回滚。测试仅使用自己的随机 schema。

[TicketOrderCreationTimeTest](../src/test/java/com/limitactivitysystem/ticketorder/service/TicketOrderCreationTimeTest.java)通过替换数据库时间读数，验证开放、截止和开始时刻及其前后 1ms。Mapper 集成测试实际执行数据库时间条件插入。

### 主表与全部明细的原子性

Controller 调用协调 Service，再通过 Spring 代理调用带有 `@Transactional(propagation = REQUIRES_NEW, isolation = READ_COMMITTED)` 的 `TicketOrderCreationService.createOrder`。主表插入取得 `orderId`，全部明细使用该 ID 在同一事务中写入。成功时一起提交，运行时异常或明细写入数量不符时整笔回滚。

`TicketOrderCreationMySqlIntegrationTest.failuresInsideTheRealCreationTransactionLeaveNoOrdersOrResourceChanges` 不使用外围测试事务，通过真实 Service 代理创建包含两条明细的订单。测试专用 MyBatis 拦截器 `OrderWriteFailure` 在以下三个位置抛出 `IllegalStateException`：

| 故障位置 | 抛错前主表行数 | 抛错前明细行数 | 回滚后主表、明细行数 |
|---|---|---|---|
| `AFTER_HEADER` | `1` | `0` | `0`、`0` |
| `AFTER_FIRST_ITEM` | `1` | `1` | `0`、`0` |
| `AFTER_ALL_ITEMS` | `1` | `2` | `0`、`0` |

生产 Service 使用批量 INSERT。为观察第一条明细已经存在、第二条尚未写入的状态，`AFTER_FIRST_ITEM` 在同一 MyBatis Executor 和 Spring 事务中执行真实的单条明细 INSERT，再抛异常。`AFTER_ALL_ITEMS` 则在生产批量 SQL 实际写入两条明细后抛异常。

拦截器只定义于 `src/test`，仅由该测试的 `@TestConfiguration` 注册，不提供生产开关或 HTTP 故障参数。故障发生前，测试读取实际数据库行与生成 ID，并确认 Spring 事务处于活动状态且连接的 `autoCommit = false`。异常离开 Service 代理后，测试再查询主表、按请求键或订单 ID 查询订单及明细，均确认记录不存在。

资源快照同时在故障发生前和回滚后比较，包含 `session_zone`、`session_seat`、`user_session_purchase`、`ticket` 的全部行。测试保留一条既有购票计数记录，确认它未被替换或修改。成功创建多条明细的测试另行确认提交后主表一行、全部明细存在，并且同样没有预占资源。

事务故障注入和 Mapper 测试需配置真实 MySQL 连接和 `DB_PASSWORD`：

```powershell
mvn '-Dtest=TicketOrderCreationMySqlIntegrationTest,TicketOrderMapperMySqlIntegrationTest' test
```

业务创建测试和时间边界测试可在已配置 MySQL 环境中执行：

```powershell
mvn '-Dtest=TicketOrderCreationMySqlIntegrationTest,TicketOrderCreationTimeTest,TicketOrderMapperMySqlIntegrationTest,TicketOrderRequestContractTest' test
```

### 幂等与并发验收

创建集成测试覆盖六个购买意图字段的冲突、相同金额不同购买内容、输入顺序变化、重复明细、购买者作用域，以及原单状态、价格、期限和资格变化后的重放。数据库中的已支付、已关闭状态由测试准备数据模拟，不表示支付或关闭业务已经实现。

[TicketOrderIdempotencyConcurrencyMySqlIntegrationTest](../src/test/java/com/limitactivitysystem/activity/integration/TicketOrderIdempotencyConcurrencyMySqlIntegrationTest.java)使用两个真实 MySQL 连接及 barrier，使双方均先查不到原单、再同时尝试 INSERT。相同载荷得到 `201 + 200`，不同载荷得到 `201 + 409`，数据库始终只有一份主表与明细，库存、座位、计数及 Ticket 不变。测试观察失败事务完成回滚后才开启重放事务，并验证调用方已有 `REPEATABLE_READ` 旧快照时仍能正确重放，且调用方事务未被标记回滚。

另有竞争用例让一个请求查不到原单后暂停，另一个请求创建并提交，再关闭窗口或设置空名单。迟到请求的新建事务失败后，仍须按原载荷重放或返回幂等冲突，不能返回新下单的时间或资格错误。

```powershell
mvn '-Dtest=TicketOrderCreationMySqlIntegrationTest,TicketOrderIdempotencyConcurrencyMySqlIntegrationTest' test
```

2026-10-24，本次相关回归按以下九个测试类的最新 Surefire 报告计，共 298 个用例，失败、错误、跳过均为零。先执行九类回归，再补充并复跑幂等并发类至 8 个用例；没有重复计算前一轮的 4 个并发用例，也没有执行整个项目的全部测试。

| 测试类 | 用例数 |
|---|---:|
| `TicketOrderCreationMySqlIntegrationTest` | 68 |
| `TicketOrderIdempotencyConcurrencyMySqlIntegrationTest` | 8 |
| `TicketOrderMapperMySqlIntegrationTest` | 19 |
| `TicketOrderCreationTimeTest` | 11 |
| `TicketOrderRequestContractTest` | 82 |
| `SecurityAccessTest` | 44 |
| `QualificationEntryPointMySqlIntegrationTest` | 29 |
| `WhitelistQualificationConcurrencyMySqlIntegrationTest` | 11 |
| `QualificationServiceTest` | 26 |

## Day 5 最终验收记录

2026-10-25 统一票单错误码，并新增两种选座模式下的 100 座不预占证据。本次执行以下六个测试类，共 256 个用例，失败、错误、跳过均为零；该记录只覆盖列出的测试类。

| 测试类 | 用例数 |
|---|---:|
| `TicketOrderCreationMySqlIntegrationTest` | 92 |
| `TicketOrderIdempotencyConcurrencyMySqlIntegrationTest` | 8 |
| `TicketOrderMapperMySqlIntegrationTest` | 19 |
| `TicketOrderRequestContractTest` | 82 |
| `SecurityAccessTest` | 44 |
| `TicketOrderCreationTimeTest` | 11 |

验收场景与测试对应如下，未注明类名的方法均位于 `TicketOrderCreationMySqlIntegrationTest`：

| 场景 | 自动化证据 |
|---|---|
| 正常自动配座与自主选座 | `autoAssignmentPreservesPreferenceWithoutChoosingSeats`、`selectedSeatingStoresIntentWithoutCheckingOrTakingSeatAvailability` |
| 未登录与禁用账号 | `anonymousRequestsCannotCreateOrders`、`disabledBuyerCannotReuseAnOldToken` |
| 未发布、场次不可用、售票窗口外 | `onlyPublishedActivitiesAcceptNewOrders`、`nonScheduledSessionsRejectNewOrders`、`creationUsesTheDatabaseTimeAndCreationWindow` |
| 当前名单拒绝 | `currentQualificationRulesApplyBeforeAnyOrderIsWritten`、`eachNewOrderSeesCommittedWhitelistChanges` |
| Zone 跨场次与 GIFT Zone | `existingZoneFromAnotherSessionIsRejectedEvenForTheSamePublisher`、`giftZoneCannotBePurchased` |
| Seat 跨区域与配座模式不一致 | `selectedSeatMustExistAndBelongToItsZone`、`everyItemMustMatchTheSessionSeatMode` |
| 相同请求重放、不同载荷冲突 | `duplicateRequestIdWithTheSamePayloadReturnsTheOriginalOrder`、`changingAnyPurchaseIntentFieldConflictsBeforeNewOrderValidation`、`equalMoneyDoesNotMakeTwoDifferentPurchasesTheSameRequest` |
| 并发同一幂等键只保留一单 | `TicketOrderIdempotencyConcurrencyMySqlIntegrationTest.uniqueKeyLoserReadsTheWinnerOnlyAfterItsOwnCreationTransactionRolledBack` |
| 真实写入故障后主表、明细全回滚 | `failuresInsideTheRealCreationTransactionLeaveNoOrdersOrResourceChanges` |
| 库存、Seat、限购不变，Ticket 不生成 | `successfulCreationRecordsOneHundredAvailableSeatsAndZeroUsedQuotaWithoutReservingEither`、`pendingOrdersNeitherReserveStockNorConsumePurchaseQuota` |
| 用户 A 查询用户 B 的订单被拒绝 | `otherUsersAndPublishersCannotReadTheBuyersOrderEvenWithAForgedBuyerId`、`directServiceCallsAlsoEnforceOrderOwnership` |

MockMvc 启用生产 Security 过滤链，业务集成测试使用真实 MySQL、独立随机 schema 和真正提交的业务事务。测试结果以最新 Surefire 报告为准，不将条件跳过计作通过。完整复跑命令为：

```powershell
mvn '-Dtest=TicketOrderCreationMySqlIntegrationTest,TicketOrderIdempotencyConcurrencyMySqlIntegrationTest,TicketOrderMapperMySqlIntegrationTest,TicketOrderRequestContractTest,SecurityAccessTest,TicketOrderCreationTimeTest' test
```
