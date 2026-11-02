# GIFT 赠票资源操作模型

本契约覆盖 Day 12 的 ALLOCATE 主链、历史查询和容量巡检：退款前置核验、操作历史迁移、发布者权限、原子分配、幂等与 PAID/GIFT 隔离。依据 requirements.md 的 GIFT-01～03、QUAL-04～05，以及 schedule.md 的 Day 12。

赠票分配 Service 与 [分配及历史 API](api/gift-allocations.md) 已接入。当前只实现 ALLOCATE，RELEASE 仍为后续扩展。Schema 测试验证存储契约，业务集成测试通过真实认证、Controller、Service 与 MyBatis 验证资源事务。

## 前置条件与资格边界

开始增加赠票表前，已重新运行 Day 11 的 70 项退款处理测试，全部通过，失败、错误、跳过均为零。其中包括 AT-09、AT-10、重复处理、限购与库存恢复、订单汇总、整笔故障回滚和退款后座位重售。正常场景对账零差异；故意损坏的测试夹具能被对账查出。退款锁顺序仍以 [locking.md](locking.md#ticket-refund-processing) 为准。

白名单变化继续遵循以下规则，GIFT 不引入领取人资格判断：

| 已有状态或未来动作 | 名单变化后的语义 |
|---|---|
| PENDING_PAYMENT 订单尝试支付 | 支付事务重新检查当前适用名单。 |
| 已成功免费预约 | 不因名单删除自动失效。 |
| 已支付 VALID Ticket | 不因名单删除自动失效。 |
| 取消预约后重新预约 | 新建时按最新名单、时间及名额判断。 |
| 退票后重新购票 | 新建与支付均按最新名单判断。 |

## 资源边界

GIFT Zone 属于 PAID 活动场次的独立座位池，价格为零。每份赠票占用一个真实的 GIFT Seat。公开售票不能选择这些座位，不能把赠票库存自动转换为 PAID 库存。

`publisher_id` 只表示执行资源操作的发布者。两张新增表不包含领取人、持有人、实名或核销字段，也不关联领取者账号。禁止增加 receiver_user_id、recipient_id、owner_user_id、real_name、id_card 或 check_in_status。不为赠票生成普通 `ticket`，不扣减领取人的 `user_session_purchase`。

## 主操作与座位明细

[V7__add_gift_inventory_operations.sql](../src/main/resources/db/migration/V7__add_gift_inventory_operations.sql) 新建两张 InnoDB 表，沿用零物理 FOREIGN KEY 的架构。

`gift_inventory_operation` 保存一次发布者资源操作：

| 字段 | 类型及约束 | 含义 |
|---|---|---|
| operation_id | BIGINT，自增主键 | 操作身份。 |
| request_id | VARCHAR(64)，非空、非全空格，utf8mb4_0900_bin | 发布者范围内的幂等键，保留大小写及完整字符串。 |
| publisher_id | BIGINT，非空且大于零 | 执行操作的发布者账号逻辑引用。 |
| session_id | BIGINT，非空且大于零 | 操作所属场次逻辑引用。 |
| zone_id | BIGINT，非空且大于零 | 操作所属 GIFT Zone 逻辑引用。 |
| operation_type | VARCHAR(16)，非空，默认 ALLOCATE | 本切片仅允许 ALLOCATE。 |
| quantity | INT，非空且大于零 | 本次占用的真实座位数。 |
| status | VARCHAR(20)，非空，默认 SUCCEEDED | 仅持久化与资源一起提交的成功操作。 |
| created_at | DATETIME(3)，非空，数据库默认时间 | 服务器记录的操作时间。 |

`gift_inventory_operation_item` 保存具体座位：

| 字段 | 类型及约束 | 含义 |
|---|---|---|
| operation_item_id | BIGINT，自增主键 | 明细身份。 |
| operation_id | BIGINT，非空且大于零 | 所属操作逻辑引用。 |
| seat_id | BIGINT，非空且大于零 | 本次操作的实际 GIFT Seat 逻辑引用。 |
| created_at | DATETIME(3)，非空，数据库默认时间 | 明细记录时间。 |

主表 `UNIQUE(publisher_id, request_id)` 保护操作级幂等。不同发布者可复用相同 request_id，但这不授予其操作他人 Zone 的权限。

明细 `UNIQUE(operation_id, seat_id)` 阻止同次操作重复记录同一座位。seat_id 不是全表唯一，因为历史需要保留同一座位在不同操作中的记录。当前是否可分配仍由 Seat 的条件更新保护，不能用历史唯一键替代资源状态检查。

查询索引包括主表的 `(zone_id, operation_id)`、`(session_id, operation_id)`、`(publisher_id, created_at, operation_id)`，以及明细的 `(seat_id, operation_id)`。明细按操作查询使用以 operation_id 开头的唯一索引，不额外建立重复索引。

`GET /publisher/gift-zones/{zoneId}/allocations` 提供按操作 ID 升序的游标分页，默认 20、最多 100 个操作；`GET /publisher/gift-allocations/{operationId}` 返回单次完整历史。两者均按当前资源归属授权，操作人的 publisher_id、时间、数量和实际 Seat 明细保持历史含义，场次结束不影响查询。读取共享父资源与 Zone 锁，不写库存或 Seat，详见 [查询锁顺序](locking.md#gift-history-queries)。

Seat 的 allocation_status 只能说明当前状态。谁操作、何时操作、一次分配几个、具体哪些 Seat 及 requestId 是否重复，都由 operation / operation_item 回答。查询不根据当前 GIFT_ALLOCATED 集合重建历史；明细不完整时暴露冲突，由巡检检查历史与当前事实是否一致。

## 操作与状态选择

当前只支持 ALLOCATE，不实现 RELEASE。操作历史不是异步任务或退款申请，不单独提交 PENDING、PROCESSING 或 FAILED。分配事务写入 SUCCEEDED 主表及明细，只有资源更新、数量校验和全部明细成功后才提交。中途失败时资源与历史一起回滚，数据库没有半次操作；调用方使用原 request_id 重试。

operation_type 和 status 使用区分大小写的排序规则，只保存规范的 ALLOCATE、SUCCEEDED，拒绝小写变体。

历史提交后不通过修改原操作来表达新的资源变动。若后续支持 RELEASE，需要新增操作类型及明确的来源操作、来源座位关联，只释放原操作中仍为 GIFT_ALLOCATED 的座位，并保留原分配记录。该扩展需新的迁移和测试，不在 V7 预留没有完整语义的 RELEASE 状态。

## 分配事务契约

分配入口的客户端字段限于 requestId 和 quantity，区域由路径确定。publisherId 来自认证上下文，sessionId 沿 Zone 的持久化归属取得，Seat 由服务器选择。领取人、客户端指定 status、createdAt、totalAmount 和其他额外字段全部拒绝，quantity 使用严格 JSON 整数解析。

同一发布者和 request_id 的请求，必须比较 session_id、zone_id、operation_type、quantity。内容相同返回原操作及原 Seat 明细，不再扣库存；内容不同拒绝且不改变资源。唯一键冲突必须先退出失败事务，再读取胜出操作并比较内容。

一个业务事务必须核对以下条件：

1. 当前账号为 ACTIVE 发布者，拥有所属 Activity 的管理权限。
2. Zone、Session、Activity 的关联有效；Activity 为 PAID、PUBLISHED，Session 为 SCHEDULED 且尚未开始，区域为 GIFT。
3. 锁定 Zone 后选择 N 个 AVAILABLE Seat，全部属于该区域。
4. 按 seat_id ASC 条件执行 AVAILABLE → GIFT_ALLOCATED，每条影响一行。
5. Zone 条件扣减 N，要求 remains_amount 至少为 N，影响一行。
6. 在同一事务写主操作及 N 条不同 Seat 明细，明细数等于 quantity，全部成功后提交。

同层多个 Zone 按 zone_id ASC，Zone 在 Seat 之前取得锁，与支付和退款的共同资源顺序兼容。数量、引用存在性、GIFT 归属、发布者权限及跨表计数不能由单行 CHECK 或逻辑引用自动保证，由 Service 事务校验及分配对账共同检查。

[GiftAllocationService](../src/main/java/com/limitactivitysystem/gift/service/GiftAllocationService.java) 在事务外协调已知请求键冲突。[GiftAllocationTransactionService](../src/main/java/com/limitactivitysystem/gift/service/GiftAllocationTransactionService.java) 使用独立 READ_COMMITTED 事务，先锁账号、Activity、Session，再锁 Zone。选择候选后按 Seat ID 升序条件占用，条件扣减 GIFT 库存，再写操作及逐座明细。写入前后检查新分配的生命周期，历史重放只校验当前权限及原意图。

实际占用 SQL 同时要求 seat_id、zone_id、AVAILABLE 及 GIFT 类型；库存 SQL 要求 GIFT 类型、原场次及足够余量。所有写入都检查影响一行。total_amount 从不由分配事务更新，例如总量 20、余量 20，分配 5 后总量仍为 20、余量为 15。

PAID 支付在锁定 Zone 后检查 PAID 类型，自动配座查询只读取该已校验 Zone 的 AVAILABLE Seat。GIFT 候选查询还在 SQL 中限制 GIFT 类型，不能从其他 GIFT Zone 或 PAID Zone 补座位。AT-06 同时覆盖公开下单、支付复查、自动配座和 GIFT 分配反向隔离。

## 分配验收与对账

[GiftAllocationMySqlIntegrationTest](../src/test/java/com/limitactivitysystem/activity/integration/GiftAllocationMySqlIntegrationTest.java) 覆盖实际分配五张、确定性选座与升序占用、库存耗尽重放、场次开始后历史重放、当前权限复查、参数拒绝、生命周期及逻辑父记录校验、不同发布者请求键复用、同 Zone 并发和跨 Zone 相同键竞争。

故障注入发生在首个及最后 Seat 占用、Zone 扣减、主操作 INSERT、首条及最后一条明细 INSERT 后，比较全部资源与历史快照不变，再复用原键重试。另有零影响行回滚、真实 Zone 锁等待跨场次开始，以及最终时间检查使完整资源和历史写入回滚的实验。

第 14 项另补两组指定实验：A 在第二个 Seat 已占用且尚未扣库存时失败；B 请求五张，在全部占座、库存扣五且已写入一条明细后失败。两组都在抛异常前读取事务内实际状态，失败后检查所有 Seat、库存及历史完整回滚，并运行零差异对账。第 15 项白名单 A–D 完整回归及本轮 88 项结果见 [赠票故障与名单变化验收](day12-whitelist-regression.md)。

[gift_invariants.sql](../tests/sql/gift_invariants.sql) 在每个业务用例结束时执行以下三类容量巡检，并检查座位类型不串池、操作明细数等于 quantity、逻辑关联及当前 Seat 状态正确、每个已分配座位有一条当前 ALLOCATE 历史、GIFT 座位没有普通 Ticket。

| 巡检 | 等式或上限 |
|---|---|
| PAID_STOCK | PAID Zone 的 total_amount = remains_amount + SOLD Seat 数。 |
| GIFT_STOCK | GIFT Zone 的 total_amount = remains_amount + GIFT_ALLOCATED Seat 数。 |
| SESSION_CAPACITY | 同场次 Σ PAID total_amount + Σ GIFT total_amount ≤ session.capacity。 |

Session 检查使用规划总量，不能把已售或已分配数量从规划容量中扣掉。正常场景返回零异常行，故意损坏的夹具必须检出问题。PAID 资源另运行既有退款对账。脚本只读，等待并发工作线程结束后运行；对应当前 ALLOCATE-only 模型，增加 RELEASE 时需同步扩展历史对账语义。

第 16～22 项补齐了三个 API、当前归属授权、历史完整性、三类容量异常检出、AT-06 无领取人绑定和最后一席并发。相关专项测试共 112 项通过，完整验收矩阵、全项目回归与对账证据见 [Day 12 完成验收](day12-completion.md)。

第 4～13 项分配主链的回归共 **342 项通过，失败 0、错误 0、跳过 0**，当时包含 GIFT 分配 72 项、Schema 44 项、退款 70 项、支付 84 项、配座 17 项、安全 44 项和名单并发 11 项。第 14、15 项新增测试后的本轮结果见上文。原分配证据见 [分配运行汇总](verification/day12-gift-allocation/run-summary.json)、[分配五张](verification/day12-gift-allocation/day12-gift-allocate-five.json)、[跨 Zone 幂等竞争](verification/day12-gift-allocation/day12-gift-cross-zone-key-race.json)及 [AT-06 双向隔离](verification/day12-gift-allocation/day12-at06-paid-gift-isolation.json)。

```powershell
	mvn '-Dtest=GiftAllocationMySqlIntegrationTest,GiftInventorySchemaMySqlIntegrationTest,TicketRefundProcessingMySqlIntegrationTest,TicketPaymentMySqlIntegrationTest,SeatAllocationMySqlIntegrationTest,SecurityAccessTest,WhitelistQualificationConcurrencyMySqlIntegrationTest' test
```

## 迁移与验证

V7 只新增两张表，不改变既有十五张业务表，不写 Seat、Zone、Ticket 或用户限购。完成迁移后共十七张业务表，`flyway_schema_history` 不计入业务表，物理外键仍为零。

[GiftInventorySchemaMySqlIntegrationTest](../src/test/java/com/limitactivitysystem/activity/integration/GiftInventorySchemaMySqlIntegrationTest.java) 使用随机隔离 MySQL 库，通过真实 Flyway 建立 V6，再应用 V7，验证旧表定义、行数和已有账号时间不变，重复 migrate 执行零条迁移。测试隔离目录不包含历史 V2 smoke seed，因为该脚本包含显式 USE，不能在隔离验收中执行。

Schema 测试覆盖精确列清单、NOT NULL、正数边界、操作与状态 CHECK、索引列顺序、同发布者请求键竞争、不同发布者复用请求键、逐操作 Seat 去重、数据库时间、历史事务回滚及零普通 Ticket 写入。它也验证不存在物理外键的实际语义，避免把正数 ID CHECK 误认为关联存在性校验。

2026-10-31 迁移阶段验证共 **195 项通过，失败 0、错误 0、跳过 0**：GIFT Schema 44 项、Day 11 退款处理 70 项、白名单资格入口 29 项、名单并发 11 项、免费预约 41 项。退款对账的 53 个正常场景均零差异，17 个故意损坏场景均检出问题。原始结果见 [运行汇总](verification/day12-gift-schema/run-summary.json)、[迁移验证](verification/day12-gift-schema/day12-gift-migration.json)、[请求键竞争](verification/day12-gift-schema/day12-gift-request-key-race.json)和 [Day 11 硬门槛对账](verification/day12-gift-schema/day11-hard-gate-reconciliation.json)。

```powershell
	mvn '-Dtest=GiftInventorySchemaMySqlIntegrationTest,TicketRefundProcessingMySqlIntegrationTest,WhitelistQualificationConcurrencyMySqlIntegrationTest,QualificationEntryPointMySqlIntegrationTest,FreeReservationMySqlIntegrationTest' test
```

MySQL 验收需要 DB_PASSWORD，跳过不算通过。结果保存在 [Day 12 Schema 验证目录](verification/day12-gift-schema)。
