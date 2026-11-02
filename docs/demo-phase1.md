# Phase 1 连续演示

按 Demo A → B → C 连续演示票务、免费预约和赠票资源。每个阶段同时核对响应与数据库终态。本轮使用现有认证、活动、订单、支付、退款、预约与 GIFT API，没有新增业务接口、表、模块或技术组件。

## 准备本轮演示数据

自动化演示使用 [phase1.json](../tests/fixtures/phase1.json) 和 [固定 fixture 说明](../tests/fixtures/README.md)：两个发布者、三个普通账号，一个 PAID 与一个 FREE 活动；PAID 活动含 SELECT_SEAT、AUTO_ASSIGN 两场及独立 PAID/GIFT Zone。演示 A 使用 SELECT_SEAT 场，B 使用首个 FREE 场，C 使用与 A 同场的 GIFT Zone。

集成测试会创建并最终销毁随机隔离库，不会将账号或资源留在应用库。手工调用下面的 API 时，在已启动应用连接的演示库中按相同配置准备数据，记录实际返回的 ID。不要将测试运行中的 ID 直接用于应用库。

| 资源 | 本轮需要的配置 |
|---|---|
| Publisher A | ACTIVE、PUBLISHER，拥有演示活动。普通注册不会授予发布者角色；发布者由已有受控种子准备。 |
| User A | ACTIVE，位于 PAID 活动白名单。FREE 活动未配置名单，公开预约。 |
| PAID Activity | PUBLISHED，已开售 10 天，停售在明天。 |
| SELECT_SEAT Session | SCHEDULED，20 天后开始，capacity=6，maxTicketsPerUser=4。 |
| PAID Zone A/B/C/D | 各一席，价格分别为 200、400、800、1200，初始库存各为一。 |
| GIFT Zone | 两席，price=0，total_amount=remains_amount=2，初始均 AVAILABLE。 |
| FREE Session | SCHEDULED，20 天后开始，capacity=2。首次预约前 quota 可尚未初始化。 |

该时间配置已超过开售 48 小时，且距离开场在 14～30 天之间，本轮退款比例为 85%。所有时间按本轮数据库时间生成，避免固定日期失效。发布前通过现有管理 API 创建资源，调用顺序如下：

| 顺序 | 调用 | 预期结果 |
|---|---|---|
| 1 | `POST /auth/login`，Publisher A | 200，保存 accessToken。 |
| 2 | `POST /publisher/activities`，分别创建 PAID、FREE | 201，保存两个 activityId。正文为 name、type、openTime、closeTime。 |
| 3 | `POST /publisher/activities/{activityId}/sessions` | 201，保存 sessionId。FREE 不填写 seatMode 或 maxTicketsPerUser。 |
| 4 | `POST /publisher/sessions/{sessionId}/zones` | 201，保存 zoneId。PAID 活动创建上述 PAID/GIFT Zone，正文为 name、zoneType、price、totalAmount。 |
| 5 | `POST /publisher/zones/{zoneId}/seats` | 201，保存 seatIds。正文为 seats 数组，每项为 rowNo、seatNo、allocationPriority。FREE 不创建 Zone 或 Seat。 |
| 6 | `POST /publisher/activities/{activityId}/publish` | 204，两个活动变为 PUBLISHED。 |
| 7 | `PUT /publisher/activities/{paidActivityId}/whitelist`，再 `POST /publisher/activities/{paidActivityId}/whitelist/members` | 200、201。成员正文为 `{"userId": <User A 的实际 ID>}`，操作人由发布者 JWT 确定。 |

资源创建的完整可执行构建过程在 [Phase1JourneyMySqlIntegrationTest](../src/test/java/com/limitactivitysystem/activity/integration/Phase1JourneyMySqlIntegrationTest.java) 的 BeforeEach 中。管理请求均使用 Publisher A 的 Bearer token。

下面的 `{{变量}}` 表示从本轮真实响应中取得并代入的值，可在 HTTP 客户端中保存。buyerToken 和 publisherToken 只保存原始 accessToken，Header 中再加 Bearer。baseUrl 指向已启动的应用，例如 `http://localhost:8080`。

每轮重新准备资源并使用新的请求键；同一业务调用重试则沿用原 requestId。不要用已完成的旧订单制造新的成交。

## Demo A：PAID 支付后部分退款

### 1. 登录并查看 PUBLISHED Activity

```http
POST {{baseUrl}}/auth/login
Content-Type: application/json

{"username":"{{buyerUsername}}","password":"{{demoPassword}}"}
```

预期 200，将 accessToken 保存为 buyerToken。

```http
GET {{baseUrl}}/activities
Authorization: Bearer {{buyerToken}}
```

```http
GET {{baseUrl}}/activities/{{paidActivityId}}
Authorization: Bearer {{buyerToken}}
```

两次均为 200，列表包含演示活动，详情 status=PUBLISHED、type=PAID。列表与详情不提供场次、区域和座位 ID；这些 ID 取自前面的实际创建响应。

### 2. 创建四张不同票价的订单

```http
POST {{baseUrl}}/ticket-orders
Authorization: Bearer {{buyerToken}}
Content-Type: application/json

{
	"requestId": "journey-four",
	"sessionId": {{selectedSessionId}},
	"items": [
		{"zoneId":{{zoneA}},"allocationMode":"SELECT_SEAT","requestedSeatId":{{seatA}},"quantity":1,"acceptNonAdjacent":false},
		{"zoneId":{{zoneB}},"allocationMode":"SELECT_SEAT","requestedSeatId":{{seatB}},"quantity":1,"acceptNonAdjacent":false},
		{"zoneId":{{zoneC}},"allocationMode":"SELECT_SEAT","requestedSeatId":{{seatC}},"quantity":1,"acceptNonAdjacent":false},
		{"zoneId":{{zoneD}},"allocationMode":"SELECT_SEAT","requestedSeatId":{{seatD}},"quantity":1,"acceptNonAdjacent":false}
	]
}
```

预期 201，保存 orderId。订单为 PENDING_PAYMENT、totalAmount=2600.00，expiresAt 为 createdAt 后十五分钟。Seat 仍 AVAILABLE，库存与限购未占用，没有 Ticket。客户端不传买家身份、价格、金额或状态。

### 3. 支付并读取生成的 Ticket

```http
POST {{baseUrl}}/ticket-orders/{{orderId}}/pay
Authorization: Bearer {{buyerToken}}
```

无正文。预期 200，status=PAID，tickets 四项，paidPrice 分别为 200、400、800、1200，全部 VALID。按 paidPrice 和 seatId 对应记录 ticketA/B/C/D，不依赖数组位置或固定 ID。

数据库预期：A～D 的 Seat 均 SOLD，四个 Zone 库存各为零，User A 在该场次 valid_count=4。

### 4. 受理 B、D 两张票的退款

```http
POST {{baseUrl}}/ticket-refunds
Authorization: Bearer {{buyerToken}}
Content-Type: application/json

{
	"requestId": "journey-refund",
	"orderId": {{orderId}},
	"ticketIds": [{{ticketB}}, {{ticketD}}],
	"reason": "USER_REQUEST"
}
```

预期 201，保存 refundId，status=PENDING。逐票 originalPrice 为 400、1200，feeRate=0.15，refundAmount 为 340、1020。金额与申请时间由服务端冻结，此时四张票仍 VALID、四个 Seat 仍 SOLD，资源尚未恢复。

### 5. 处理原申请并确认 Seat 恢复

```http
POST {{baseUrl}}/ticket-refunds/{{refundId}}/process
Authorization: Bearer {{buyerToken}}
```

无正文。预期 200，申请及两条明细 SUCCEEDED，result=SIMULATED_REFUND_SUCCEEDED；不重新提交票、价格、费率或金额。

```http
GET {{baseUrl}}/ticket-orders/{{orderId}}
Authorization: Bearer {{buyerToken}}
```

预期 200，订单为 PARTIALLY_REFUNDED。订单详情不包含逐票当前状态，逐票状态按下面的数据库查询核对。

| 对象 | Ticket | Seat | Zone remains_amount |
|---|---|---|---:|
| A，200 | VALID | SOLD | 0 |
| B，400 | REFUNDED | AVAILABLE | 1 |
| C，800 | VALID | SOLD | 0 |
| D，1200 | REFUNDED | AVAILABLE | 1 |

该账号场次 valid_count=2，成功退款金额合计 1360.00。退款不改变订单原成交总额。

## Demo B：FREE 预约、取消、重新预约

继续使用 User A 的 JWT，FREE 活动未配置名单，名额初始为二。三个请求均无正文。

| 顺序 | 调用 | 预期响应 | 数据库预期 |
|---|---|---|---|
| 1 | `POST /sessions/{freeSessionId}/reservations` | 201，保存 reservationId，status=ACTIVE。 | quota.remaining=1，ACTIVE 一条。 |
| 2 | `POST /reservations/{reservationId}/cancel` | 200，status=CANCELLED，cancelledAt 非空。 | quota.remaining=2，旧记录保留。 |
| 3 | `POST /sessions/{freeSessionId}/reservations` | 201，新的 reservationId，status=ACTIVE。 | quota.remaining=1，新旧两条历史，同账号同场次 ACTIVE 仅一条。 |

HTTP Header 都是 `Authorization: Bearer {{buyerToken}}`。重新预约遵循当前名单、窗口与库存，历史预约不授予优先权。本轮 FREE 只有这一组预约操作，终态为一条 CANCELLED、一条 ACTIVE。

## Demo C：GIFT 分配后仍不能公开购买

### 1. Publisher 登录并分配一份

```http
POST {{baseUrl}}/auth/login
Content-Type: application/json

{"username":"{{publisherUsername}}","password":"{{demoPassword}}"}
```

预期 200，保存 accessToken 为 publisherToken。

```http
POST {{baseUrl}}/publisher/gift-zones/{{giftZoneId}}/allocations
Authorization: Bearer {{publisherToken}}
Content-Type: application/json

{"requestId":"demo-phase1-gift","quantity":1}
```

预期 201，operationType=ALLOCATE、status=SUCCEEDED、quantity=1，seatIds 恰好一项。保存 operationId 和 giftSeatId。GIFT Zone 总量仍为二，余量从二减为一，该 Seat 由 AVAILABLE 变为 GIFT_ALLOCATED，另一席仍 AVAILABLE。

### 2. 查看操作历史

```http
GET {{baseUrl}}/publisher/gift-allocations/{{operationId}}
Authorization: Bearer {{publisherToken}}
```

预期 200，返回原操作 ID、时间、数量和相同 seatIds。publisherId 表示操作人，响应没有领取人账号、实名或核销字段。操作及逐座明细来自持久化历史；GIFT 不生成普通 Ticket 或领取人账号。

### 3. User A 尝试公开购买 GIFT Seat

```http
POST {{baseUrl}}/ticket-orders
Authorization: Bearer {{buyerToken}}
Content-Type: application/json

{
	"requestId":"demo-gift-rejected",
	"sessionId":{{selectedSessionId}},
	"items":[
		{"zoneId":{{giftZoneId}},"allocationMode":"SELECT_SEAT","requestedSeatId":{{giftSeatId}},"quantity":1,"acceptNonAdjacent":false}
	]
}
```

预期 400，code=GIFT_ZONE_NOT_PURCHASABLE，不创建新订单或 Ticket，不改变任何资源。再把 requestedSeatId 换成同 GIFT Zone 尚未分配的另一席，使用另一 requestId，仍然返回相同错误。公开不可购买的原因是 GIFT 资源池隔离，不能只依赖当前 Seat 是否已经被占用。

本轮最终有四张普通 Ticket，其中两张 VALID、两张 REFUNDED；GIFT 一席已分配、一席可用，账号数保持不变。AUTO_ASSIGN 的独立隔离回归见 [Day 12 验收](day12-completion.md)。

## 核对数据库终态

在演示库的 SQL 控制台，将会话变量 @demo_order_id、@demo_user_id、@demo_selected_session_id、@demo_free_session_id、@demo_gift_zone_id、@demo_refund_id 绑定为本轮实际 ID，再执行以下只读查询。

```sql
SELECT t.ticket_id, t.paid_price, t.status AS ticket_status,
       s.seat_id, s.allocation_status, z.remains_amount
FROM ticket t
JOIN ticket_order_item i ON i.order_item_id = t.order_item_id
JOIN session_seat s ON s.seat_id = t.seat_id
JOIN session_zone z ON z.zone_id = s.zone_id
WHERE i.order_id = @demo_order_id
ORDER BY t.paid_price;

SELECT status FROM ticket_order WHERE order_id = @demo_order_id;
SELECT valid_count FROM user_session_purchase
WHERE user_id = @demo_user_id AND session_id = @demo_selected_session_id;
SELECT SUM(refund_amount) AS refunded_amount
FROM ticket_refund_item WHERE refund_id = @demo_refund_id AND status = 'SUCCEEDED';

SELECT reservation_id, status, cancelled_at FROM free_reservation
WHERE user_id = @demo_user_id AND session_id = @demo_free_session_id
ORDER BY reservation_id;
SELECT capacity, remaining FROM free_session_quota WHERE session_id = @demo_free_session_id;

SELECT z.total_amount, z.remains_amount, s.seat_id, s.allocation_status
FROM session_zone z JOIN session_seat s ON s.zone_id = z.zone_id
WHERE z.zone_id = @demo_gift_zone_id ORDER BY s.seat_id;
```

所有请求结束后，运行 [invariants.sql](../tests/sql/invariants.sql)、[orphans.sql](../tests/sql/orphans.sql)、[reservation_invariants.sql](../tests/sql/reservation_invariants.sql)、[refund_invariants.sql](../tests/sql/refund_invariants.sql)、[gift_invariants.sql](../tests/sql/gift_invariants.sql)。每个结果集均应为空。自动化演示在各阶段提交后，用同一个只读 REPEATABLE_READ 快照执行这五组脚本。

## 自动演示与测试方式

配置可用的本地 MySQL 和 DB_PASSWORD，在项目根目录执行：

```powershell
	mvn '-Dtest=Phase1JourneyMySqlIntegrationTest#demoABCRunsPaidFreeAndGiftContinuouslyAgainstCommittedDatabaseState' test
```

测试先重建固定世界，再按 A → B → C 连续调用现有入口；没有通过直接改业务表来制造支付、退款、预约或 GIFT 成功。每步核对响应字段、影响的数据库状态与资源不变量。专用证据见 [Demo 执行目录](verification/phase1-demo)，阶段全量回归另见 [Phase 1 Test Report](reports/test-report.md)。

2026-11-01 本次专用测试 **1 项通过，失败 0、错误 0、跳过 0**，三条演示连续完成。包含 fixture 就绪与最终检查共九次对账，每次执行五组脚本，均零异常行。结果见 [执行汇总](verification/phase1-demo/run-summary.json) 与 [逐步业务证据](verification/phase1-demo/day13-demo-abc.json)。该结果与之前 1529 项全量回归分开记录，本次没有重新运行整套测试。

本次使用的工具和作用如下：

| 工具 | 实际使用方式 |
|---|---|
| PowerShell、Maven、Surefire | 执行指定 JUnit 测试并读取实际通过、失败、错误与跳过数量。 |
| JUnit 5、Spring Boot Test、MockMvc | 启动真实 Spring 上下文，通过 Security 过滤链和 Controller 发送请求。MockMvc 在进程内执行，没有启动浏览器或对外 TCP 演示客户端。 |
| Spring Service、MyBatis、MySQL | 使用真实业务事务和 SQL，提交或回滚后检查持久化状态。 |
| Flyway、隔离测试 DataSource | 在随机测试库建立 V7 测试世界，结束后销毁；不依赖应用库已有 ID。 |
| JdbcTemplate、SQL 巡检 | 核对票、座位、库存、限购、预约与操作历史；验证正常终态无差异。 |
| Git 与本地脚本检查 | 检查补丁格式、文档链接及证据文件，不代替业务测试。 |

没有引入 Redis、RabbitMQ、Product 或 SKU。支付与退款仍是现有模拟业务，录屏和真实支付渠道不属于本轮演示。
