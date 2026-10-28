# 支付资源组件与并发验收

Day 6 的订单行锁、限购、库存与配座组件已接入完整支付事务。Day 7 实现 Ticket 签发、最终 PAID 条件更新、支付幂等和缺货后独立关闭。本页记录内部组件与验收，[HTTP 契约](api/ticket-payments.md)单独维护。

## 调用边界

`TicketPaymentService.pay(orderId)` 使用 `NOT_SUPPORTED` 协调；实际写入由另一个 Spring Bean `TicketPaymentTransactionService` 的 `REQUIRES_NEW`、`READ_COMMITTED` 事务完成：

1. `TicketOrderMapper.findByIdForUpdate(orderId)` 锁定订单，检查当前买家、状态、数据库有效期和生命周期。
2. `QualificationService.checkAgain` 检查当前账号与当前名单，保留资格共享锁。
3. `TicketPaymentResourceService.occupyPurchaseQuota` 使用可信买家、已存订单场次、合计数量及场次限购值占用额度。
4. `SeatAllocator.allocate(order.sessionId, persistedItems)` 锁齐全部 Zone、扣减库存、选座并按全单 Seat ID 升序条件占用。
5. `TicketIssuanceService` 使用 `Allocation(orderItemId, zoneId, seatId)` 逐票写入，核对每条明细数量与成交价快照。
6. `markPaidIfPayable` 条件更新 PAID 和数据库 `paid_at`，要求影响一行，再复核数据库期限并返回已存 Ticket；整笔一起提交。

Day 6 的未完成流程保护已由完整事务替代。`POST /ticket-orders/{orderId}/pay` 已开放。订单锁内检查买家与 ACTIVE 账号；已经 PAID 时直接返回原 Ticket，不重复资格查询或资源写入。

资源组件使用 `MANDATORY`，不独立提交；配座入口额外要求事务显式使用 `READ_COMMITTED`，其他隔离级别在 Zone 与 Seat 写入前被拒绝。支付事务独立于调用方外围事务，因此不能把已完成支付视作外围事务可回滚的子步骤。

仅当支付抛出 `INSUFFICIENT_INVENTORY` 且代理已经完成回滚，外层才调用 `TicketPaymentFailureService`，通过新的短事务重锁订单并条件关闭。技术异常不进入该分支。若另一支付已提交 PAID，关闭分支返回原 Ticket；它不能覆盖已支付结果。

这些方法是内部业务入口，参数来自已校验并锁定的订单、明细和场次，不从支付请求重新接收 `userId`、限购值、数量、区域或选座偏好。单独调用底层 Mapper 不代表已完成身份、生命周期或资格验证。

## 当前身份、资格与时间

订单锁定后先比较可信 JWT 身份与 `buyer_id`，再通过账号共享锁确认当前 `ACTIVE`。`CurrentUserProvider.getAuthenticatedUserId()` 只解析已认证主体 ID，不能单独作为 ACTIVE 授权；原有 `getCurrentUserId()`、`getCurrentActiveUser()` 继续检查数据库账号状态。支付再次调用 `QualificationService.checkAgain`，从当前配置应用 Session 覆盖、Activity 继承及显式空名单规则。

待支付分支要求 Activity 仍为 `PUBLISHED`、`PAID`；Session 仍为 `SCHEDULED` 且尚未开始。数据库时间必须满足 `created_at <= now < expires_at`。资格等待后及配座后复核期限；最终 PAID 条件 UPDATE 再次检查订单和场次截止，并在语句返回后读取数据库时间复核。

支付不检查当前时间是否仍在 Activity 创建窗口内。停售前创建、尚未过期且场次仍允许交易的旧单可继续支付。`target/verification/payment-after-sales-close.json` 记录旧单实际完成支付并签发两张 Ticket。

## 最小 Mapper

| Mapper | 新增方法与约束 |
|---|---|
| `TicketOrderMapper` | `findByIdForUpdate`；`markPaidIfPayable`；`closePendingForInsufficientInventory`。锁定与写入要求已有事务 |
| `UserSessionPurchaseMapper` | `ensureExists`，重复初始化不重置计数；`tryIncrease`，数量为正且不突破传入可信限购值时才增加 |
| `SessionZoneMapper` | `findByIdForUpdate`；`decreaseStock`，只扣 PAID Zone，正数数量且余量足够时影响一行 |
| `SessionSeatMapper` | `findAvailableByZoneId`，禁用缓存读取可用座位；`occupySeat`，仅匹配 Seat ID、Zone ID 和 AVAILABLE 才转为 SOLD |
| `TicketMapper` | `insert` 由数据库生成 Ticket ID 与签发时间；`findByOrderId` 按已存明细查询，显式字段映射并禁用缓存 |

限购初始化只保证计数行存在，不表示购票。限购与库存 Service 对条件更新要求 `affectedRows == 1`；配座协调器对每次占座也要求一行。任一失败都抛业务异常，由外层事务回滚，不能保留之前的计数、库存或座位写入。

## 两种配座

`SeatAllocator` 校验场次、明细模式、区域归属和 PAID 类型；对重复区域汇总数量，拒绝不同订单的明细混用及数量溢出。所有 Zone 按 `zone_id` 升序锁齐，再扣库存，随后才读取或写入 Seat。

`SelectedSeatAllocator` 校验每个原请求 Seat 存在、属于对应 Zone 且当前 AVAILABLE。协调器拒绝重复 Seat，统一排序后逐个条件占用。已经被他人占用的指定座位不会替换成别的座位。

`AutoSeatAllocator` 只计算候选，不执行 SQL。当前规则如下：

1. 可用候选按 `allocation_priority`、`row_no`、`seat_no`、`seat_id` 升序排列。
2. 按候选顺序依次尝试起点，在同一 Zone、同一排寻找连续递增的 N 个 `seat_no`，使用第一个完整块。
3. 没有连座时，只有已存 `accept_non_adjacent = true` 才取同一优先序的前 N 个可用座位。
4. 可用数不足返回 `SEAT_REQUIREMENT_NOT_SATISFIED`；数量足够但没有连座、且用户不接受非连座时，返回 `ADJACENT_SEATS_UNAVAILABLE`。

同单多条自动配座明细按已存明细 ID 处理，并排除前面明细已选择的 Seat。返回的分配结果保留明细对应关系。无论哪种模式，实际占用都在候选全部确定后按全单 `seat_id` 升序进行；候选优先级不作为锁获取顺序。

Zone 锁覆盖可用读取、候选计算、库存与 Seat 写入，所有合规配座写路径都遵守它。`READ_COMMITTED` 与禁用缓存保证等待 Zone 锁后读取已提交的新状态。条件占用仍是最后一道检查，失败时回滚整个资源事务，不局部换座或在原事务中继续重试。

## 支付失败原因

| code | HTTP 语义 | 含义 |
|---|---:|---|
| `ORDER_NOT_FOUND` | 404 | 锁定查询没有对应订单 |
| `ORDER_ACCESS_DENIED` | 403 | 当前身份不是订单买家 |
| `ORDER_NOT_PENDING` | 409 | 订单不是待支付状态 |
| `ORDER_EXPIRED` | 409 | 数据库时间已到达或超过订单期限 |
| `USER_DISABLED` | 403 | 锁定复查发现账号已不存在或不再 ACTIVE |
| `QUALIFICATION_DENIED` | 403 | 当前适用名单不允许该买家参与 |
| `SESSION_NOT_AVAILABLE` | 409 | 场次缺失、取消、结束、已经开始或订单作用域失效 |
| `ACTIVITY_NOT_FOUND` / `ACTIVITY_NOT_PUBLISHED` | 404 / 409 | 活动缺失或生命周期不允许交易 |
| `PURCHASE_LIMIT_EXCEEDED` | 409 | 不能取得足够限购额度 |
| `INSUFFICIENT_INVENTORY` | 409 | PAID Zone 条件扣减影响零行 |
| `SEAT_NOT_AVAILABLE` | 409 | 指定 Seat 不可用或最终条件占用失败 |
| `ADJACENT_SEATS_UNAVAILABLE` | 409 | 无所需连座，且已存偏好不接受非连座 |
| `INVALID_SEAT_RELATION` | 409 | 已存指定 Seat 不存在或不属于对应 Zone |
| `SEAT_REQUIREMENT_NOT_SATISFIED` | 409 | 自动配座的当前可用座位数量不足 |
| `ORDER_PAYMENT_CONFLICT` | 409 | 最终条件更新未影响一行，且时间复查没有更具体的拒绝原因 |
| `INVALID_PAYMENT_REQUEST` | 400 | 支付正文包含客户端字段 |

真实请求在安全过滤链就发现账号已禁用时，按现有 `401` 认证错误处理；`USER_DISABLED` 覆盖已认证后在支付事务内复查到账号不可用的情况。`QUALIFICATION_DENIED` 复用统一资格异常。

创建时的 `PURCHASE_LIMIT_PRECHECK_FAILED`、`INSUFFICIENT_CURRENT_INVENTORY` 仍表示预检查，语义独立。其他资源、父子归属及模式错误继续使用既有具体错误码。无事务、错误隔离级别、Ticket 数量或价格与快照不符属于内部错误，整笔回滚，不伪装成缺货。

## 行锁等待实验

自动锁等待实验要求 MySQL 已开启 Performance Schema，测试账号可查询 `data_lock_waits`、`data_locks` 和 `threads`。这些观察查询只读取元数据，不修改锁或业务表。

[TicketPaymentResourceMySqlIntegrationTest](../src/test/java/com/limitactivitysystem/activity/integration/TicketPaymentResourceMySqlIntegrationTest.java)使用两个独立连接和事务：

1. A 执行订单主键 `FOR UPDATE`，通过 latch 保持事务未结束。
2. B 对同一订单执行同样的锁定查询。
3. 观察连接在 `performance_schema.data_lock_waits`、`threads`、`data_locks` 中读取真实等待关系，要求目标为隔离测试库的 `ticket_order`、索引为 `PRIMARY`、状态为 `WAITING`。
4. A 提交或回滚后，B 继续并取得原订单。

测试另行确认 A 持锁时普通 SELECT 仍能读取已提交值，另一订单主键也可独立锁定。改变 A 所持 Java 对象的字段并不写入数据库。这里验证的是数据库索引记录的事务锁，不是对象监视器。

2026-10-25 实测提交和回滚两种结束方式均通过，观察到 `RECORD`、`X,REC_NOT_GAP` 等待。原始观察结果写入：

- `target/verification/ticket-order-lock-wait-COMMIT.json`
- `target/verification/ticket-order-lock-wait-ROLLBACK.json`

若手工复现，两个连接应连接同一隔离测试库，并将示例中的 `1` 替换为该库已存在的订单 ID：

```sql
-- 连接 A
START TRANSACTION;
SELECT * FROM ticket_order WHERE order_id = 1 FOR UPDATE;
-- 保持事务，不提交。

-- 连接 B
START TRANSACTION;
SELECT * FROM ticket_order WHERE order_id = 1 FOR UPDATE;
-- 等待 A。

-- 回到 A
COMMIT;
-- B 的查询继续，随后在 B 执行 COMMIT。
```

锁范围取决于实际索引扫描。这里是已存在主键的精确匹配，不据此推断范围查询也只锁返回的行数。机制依据：[MySQL 锁定读](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)、[不同语句设置的锁](https://dev.mysql.com/doc/refman/8.4/en/innodb-locks-set.html)、[data_lock_waits](https://dev.mysql.com/doc/refman/8.4/en/performance-schema-data-lock-waits-table.html)。

## 自动化验证与证据

| 测试类 | 本次用例数 | 重点 |
|---|---:|---|
| `TicketPaymentResourceMySqlIntegrationTest` | 23 | 行锁等待、事务边界、初始化竞争、原子限购、库存零更新、同 Seat 条件占用 |
| `SeatAllocationMySqlIntegrationTest` | 17 | 两种配座、全部 Zone 先锁定、全单 Seat ID 升序、父子关系、失败回滚与 Zone 等待后刷新候选 |
| `AutoSeatAllocatorTest` | 13 | 连座优先、候选排序、拒绝与接受非连座、跨排和缺口、数量不足及整数边界 |
| `TicketPaymentMySqlIntegrationTest` | 84 | 完整支付、七点故障矩阵、自动 SQL 对账及反向验证、并发、真实指标与日志、SQL 索引计划 |
| `PaymentTelemetryTest` | 17 | 结果分类、计数与计时、重放、关闭竞争、异常原样传播与日志脱敏 |
| `ObservabilityTest` | 7 | 既有健康检查、管理端点认证、HTTP 指标与请求日志 |
| `TicketPaymentRequestContractTest` | 33 | 真实 Security 过滤链、无字段请求、拒绝客户端敏感字段、两种消费角色及稳定错误响应 |
| `TicketPaymentTimeValidationTest` | 8 | 数据库时间在过期/开场前 1ms、边界及后 1ms，等待资格期间过期、配座期间开场 |

资源类、配座类和完整支付类使用真实 MySQL；算法与精确时间边界使用单元测试，HTTP 契约测试仅替换业务 Service。并发实验通过独立连接/事务竞争，没有把两个操作放在同一个测试事务后当作并发证据。

| 必验实验 | 测试入口与结果 |
|---|---|
| 两线程锁同一 Order | 资源类 `aSecondConnectionReallyWaitsOnTheOrderPrimaryKeyUntilCommitOrRollback`：观测真实锁等待，提交或回滚后继续 |
| 限购 4、原计数 2、两个事务各 +2 | 资源类 `concurrentQuotaAcquisitionCannotExceedFourEvenDuringFirstInitialization`：只允许一个取得，最终 4 |
| Zone 剩 1、两个事务各 -1 | 资源类 `twoStockUpdatesCanAcquireTheLastUnitOnlyOnce`：影响行数分别为 1 与 0，最终 0 |
| 两订单竞争同一 Seat | 配座类 `selectedSeatCompetitorsCoordinateOnTheZoneAndOnlyOneCanAcquireTheSeat`：只有一个取得 Seat，失败方额度和库存回滚 |
| AUTO_ASSIGN 需要 3 连座但没有 | 支付类 `threeScatteredSeatsRespectTheSavedNonAdjacentChoice`：false 拒绝并回滚，true 正式支付并签发 3 张 Ticket |

限购并发实测：已购 2、限购 4、两线程各加 2，最终为 4；首次计数行竞争时也只有一行，且只有一个越限前的合法占用成功。证据为 `target/verification/quota-race-existing.json` 和 `quota-race-first-initialization.json`。

自动配座竞争实测：B 曾读取到 3 个可用座位，随后等待 A 的 Zone 锁；A 取得 2 个并提交后，B 在锁内重新读取到 1 个，最终两者取得 3 个不同 Seat，余量为 0。证据为 `target/verification/auto-seat-zone-coordination.json`。指定 Seat 的竞争也只允许一个事务取得，失败方的额度和库存回滚。

回滚预演原计数 2、库存 10。观察到限购 +2、库存 -2 和第一个 Seat UPDATE 成功，此时同一支付连接内为计数 4、库存 8、Seat 为 SOLD；独立写入者使第二个 Seat 条件占用失败后，计数恢复 2、库存恢复 10、第一把 Seat 恢复 AVAILABLE。第二个 Seat 保留独立事务的 SOLD，不会被另一个事务的回滚覆盖。证据为 `target/verification/ticket-payment-failure-rollback.json`。订单仍待支付且没有新增 Ticket。

支付类还验证同订单的两个 HTTP 请求先真实等待 Order 锁，再返回完全相同的结果；库存、Seat、限购及 Ticket 只生效一次。记录为 `target/verification/payment-same-order-idempotency.json`。第二个 Zone 缺货时，第一个 Zone 与额度恢复后才进入独立关闭事务，记录为 `payment-stock-failure-independent-close.json`。

Day 7 核心并发补验覆盖最后库存、AT-05、同 Seat 与同订单重复支付。AT-05 使用初始 `valid_count = 0`、限购 4、两张各买 3 张的订单，最终一单成功且计数为 3；原有初始 2、两单各买 2 的回归保留。不同订单通过 barrier 在独立连接中竞争，失败方无 Ticket，额度和库存只保留成功订单的效果。同 Seat 仅有一张 VALID Ticket，同订单两个请求均为 200。证据汇总为 `target/verification/day7-core-concurrency-test-summary.json`。

时间实验在 Order、Zone 锁上观察到真实等待，并在数据库期限到达后释放锁；另在 PAID UPDATE 前后暂停至订单过期或场次开始。八种组合都返回具体时间错误，订单、全部 Ticket 和资源恢复原快照。限购、库存、Seat、Ticket 及最终 PAID 更新前后注入的技术故障也不留下局部提交。

七个指定故障点逐项观察实际写入，再核对完整快照与 [payment_invariants.sql](../tests/sql/payment_invariants.sql)。四组核心并发、缺货关闭竞争及锁等待跨期测试结束后自动运行同一 SQL。指标、脱敏日志、SQL 规则和矩阵详见[支付故障矩阵、对账与可观测性](payment-verification.md)。

已配置 MySQL 连接与 `DB_PASSWORD` 后，在项目根目录运行本轮新增测试：

```powershell
mvn '-Dtest=TicketPaymentMySqlIntegrationTest,TicketPaymentRequestContractTest,TicketPaymentTimeValidationTest,TicketPaymentResourceMySqlIntegrationTest,SeatAllocationMySqlIntegrationTest,AutoSeatAllocatorTest' test
```

2026-10-26 Day 7 初版支付专项三类共 106 个用例通过；当时连同 Day 5、Day 6 与身份相关回归，共十三类 433 个用例，失败、错误、跳过均为零，其中六个真实 MySQL 类执行 224 个用例。该版报告保留在 `target/verification/day7-payment-test-summary.json`。核心并发补验后单独复跑支付类，66 个真实 MySQL 用例全部通过，没有失败、错误或跳过。完整相关回归命令为：

```powershell
mvn '-Dtest=TicketPaymentMySqlIntegrationTest,TicketPaymentRequestContractTest,TicketPaymentTimeValidationTest,CurrentUserProviderTest,TicketPaymentResourceMySqlIntegrationTest,SeatAllocationMySqlIntegrationTest,AutoSeatAllocatorTest,TicketOrderCreationMySqlIntegrationTest,TicketOrderIdempotencyConcurrencyMySqlIntegrationTest,TicketOrderMapperMySqlIntegrationTest,TicketOrderRequestContractTest,SecurityAccessTest,TicketOrderCreationTimeTest' test
```

测试使用独立随机 schema，结束时清理。记录以该次 Surefire 报告和生成快照为准，不将跳过计作通过。完整锁顺序见 [locking.md](locking.md#ticket-payment-resources)，决策见 [ADR-005](decisions.md#adr-005支付资源以订单优先的统一锁顺序协调)。

2026-10-26 故障矩阵、可观测性和自动对账补验后，本轮相关回归执行十五类 476 个用例，其中六个真实 MySQL 类共 243 个用例，失败、错误、跳过均为零。报告与七点矩阵汇总为 `target/verification/day7-payment-atomicity-test-summary.json`。

```powershell
mvn '-Dtest=TicketPaymentMySqlIntegrationTest,PaymentTelemetryTest,ObservabilityTest,TicketPaymentRequestContractTest,TicketPaymentTimeValidationTest,CurrentUserProviderTest,TicketPaymentResourceMySqlIntegrationTest,SeatAllocationMySqlIntegrationTest,AutoSeatAllocatorTest,TicketOrderCreationMySqlIntegrationTest,TicketOrderIdempotencyConcurrencyMySqlIntegrationTest,TicketOrderMapperMySqlIntegrationTest,TicketOrderRequestContractTest,SecurityAccessTest,TicketOrderCreationTimeTest' test
```
