# Day 10 普通门票退款申请契约

本契约在实现退款入口前冻结，依据 requirements.md 的 REF-01～REF-04 和 schedule.md 的 Day 10。退款申请与逐票明细为一对多；受理成功均为 PENDING。Day 11 的生效事务再负责退票、恢复座位、库存与限购额度。

## 领域模型

`ticket_refund_request` 保存 refund_id、buyer_id、order_id、request_id、reason、requested_at、status、created_at，并保存政策版本 ORDINARY_V1、活动开售时间与场次开始时间快照。`UNIQUE(buyer_id, request_id)` 保护申请幂等。request_id 最长 64 字符，按大小写和完整字符串区分；reason 为非空文本，最长 500 字符。

`ticket_refund_item` 保存 refund_item_id、refund_id、ticket_id、original_price、fee_rate、refund_amount、status、failure_reason。original_price 来自每张 Ticket.paid_price，采用 DECIMAL(10,2)；fee_rate 为手续费比例，采用 DECIMAL(5,4)；refund_amount 为 original_price × (1 − fee_rate)，逐票保留两位、HALF_UP。客户端不能提交金额或费率。

两表都只有 PENDING、PROCESSING、SUCCEEDED、FAILED 四个状态。允许的处理路径为 PENDING → PROCESSING → SUCCEEDED，或 PENDING / PROCESSING → FAILED；申请接口只写 PENDING。同一 ticket 在 PENDING、PROCESSING、SUCCEEDED 明细中最多出现一次，由生成列唯一键保护。FAILED 释放该键，保留历史。两表沿用项目的逻辑关联、无物理 FK 规则。

## 申请时间与政策快照

认证通过后、等待业务锁之前，从专用 ticketRefundClock 读取一次服务器时间作为 requested_at。生产 Bean 为 Clock.system，ticket-refund.time-zone 显式配置为 Asia/Shanghai，与 JDBC 的 DATETIME 时区一致。保存前截断到毫秒，匹配 DATETIME(3)。后续所有政策判断使用这个值。重放返回原申请及其 requested_at、逐票金额，处理阶段使用已存快照。

Clock 在受理入口注入。政策计算器是纯函数，只接收已保存的申请时间，不读取当前时间；同一申请的多张票因此共享同一政策时点。测试通过 Clock.fixed 固定时间，或替换为可切换 Fixed Clock 的测试时钟。

政策先拒绝 requested_at ≥ start_time，再判断活动统一开售后的 [open_time, open_time + 48h) 免费窗口；随后按距开始超过 30 天免费、超过 14 天至多 30 天手续费 15%、超过 10 天至多 14 天手续费 40%、至多 10 天拒绝。所有区间使用精确时间。舍入采用逐票 HALF_UP，是本次实现决定。

## 政策计算器输入与输出

[TicketRefundPolicy.evaluate](../src/main/java/com/limitactivitysystem/refund/service/TicketRefundPolicy.java) 接收 requestedAt、activityOpenTime、sessionStartTime、paidPrice 四个参数。[OrdinaryTicketRefundPolicy](../src/main/java/com/limitactivitysystem/refund/service/OrdinaryTicketRefundPolicy.java) 实现普通退款阶梯；后续特殊退款可以实现同一接口。

[TicketRefundDecision](../src/main/java/com/limitactivitysystem/refund/model/TicketRefundDecision.java) 返回以下字段：

| 字段 | 含义 |
|---|---|
| allowed | 是否允许普通退款 |
| feeRate | 手续费比例，允许时为 0、0.15 或 0.40 |
| refundRate | 用户退款比例，分别为 1、0.85 或 0.60 |
| feeAmount | 实际手续费金额，为原价减已舍入退款金额 |
| refundAmount | 每张 Ticket 的退款金额，保留两位 HALF_UP |
| rejectionReason | 拒绝原因码；允许时为 null |

拒绝结果没有金额报价，四个比例、金额字段为 null，并带 REFUND_SESSION_STARTED 或 REFUND_WINDOW_CLOSED。受理服务将原因转换为 409。服务端成交价为空、非正数或含亚分金额时，抛出独立的 REFUND_AMOUNT_CALCULATION_ERROR，返回 500 并回滚。

399.00、手续费 15% 的结果为 feeRate=0.1500、refundRate=0.8500、feeAmount=59.85、refundAmount=339.15。退款金额逐票舍入，手续费由原价减退款金额得到，保证两者合计等于成交价。

10 月 1 日 20:00 正式开售时，免费截止为 10 月 3 日 20:00。10 月 2 日 18:00 才买票不会延长该截止。19:30 的申请仍在免费窗口；20:00 及之后回到距离开始时间的阶梯。场次已开始时始终优先拒绝。

## 精确时间测试矩阵

| 边界 | 前 1ms | 精确时刻 | 后 1ms |
|---|---|---|---|
| open + 48h，场次距申请约 13 天 | 全额 | 退款 60% | 退款 60% |
| start − 30d，已过免费窗口 | 全额 | 退款 85% | 退款 85% |
| start − 14d，已过免费窗口 | 退款 85% | 退款 60% | 退款 60% |
| start − 10d，已过免费窗口 | 退款 60% | 拒绝 | 拒绝 |
| start，仍在开售 48h 窗口 | 全额 | 拒绝 | 拒绝 |

场次开始前 1ms 若已过免费窗口，也会因距开始不足十天被拒绝。开始边界另外使用仍在免费窗口的夹具，验证“场次已开始”优先于免费规则。所有边界由 Fixed Clock 驱动，不使用 Thread.sleep。

## API 与所有权

`POST /ticket-refunds` 要求 JWT，正文只接受 requestId、orderId、ticketIds、reason。ticketIds 非空、元素为正整数、不能重复；传入全部门票即申请整单退款，传入子集即部分退款。

入口从 CurrentUserProvider 获取当前账号，检查订单存在且 buyer_id 匹配。每张票通过 Ticket → TicketOrderItem → TicketOrder 证明属于该订单与买家，并要求 Ticket.status=VALID、订单为 PAID 或 PARTIALLY_REFUNDED。普通申请要求 PAID 活动、已发布活动与 SCHEDULED 场次。退款不重新检查购票白名单。

相同 requestId、orderId、reason 和相同票集合返回原单；票的输入顺序不影响幂等。相同 requestId 对应不同内容返回 409。不同 requestId 申请同一处理中或已成功退票也返回 409。任一票无效，整笔申请回滚。

## 四票价申请实验

实验通过真实下单和模拟支付产生同一订单的四张票，活动开售已超过 48 小时，场次距申请约 20 天，适用 15% 手续费。仅申请 B、D：

| Ticket | 实际成交价 | 是否申请 | RefundItem.originalPrice | feeRate | refundAmount |
|---|---:|---|---:|---:|---:|
| A | 200.00 | 否 | — | — | — |
| B | 400.00 | 是 | 400.00 | 0.1500 | 340.00 |
| C | 800.00 | 否 | — | — | — |
| D | 1200.00 | 是 | 1200.00 | 0.1500 | 1020.00 |

主表不保存 total_refund。测试读取实际持久化明细的 SUM(refund_amount)，并与响应逐项求和比较，均为 **1360.00**。主申请和两条明细为 PENDING，四张票仍为 VALID，四个 Seat 仍为 SOLD，各 Zone 余量与 valid_count 均保持原值。

实验对应 `at09FourDifferentPricesRefundOnlyTheSelectedTicketsAtTheirActualPrices`，原始明细、合计和资源断言记录在 [day10-at09-four-prices-pending.json](verification/day10-final/day10-at09-four-prices-pending.json)。

## 错误语义映射

对外错误采用项目已有的 REFUND_ 前缀。语义与 code 的对应如下：

| 语义 | HTTP / code |
|---|---|
| ORDER_NOT_FOUND | 404 REFUND_ORDER_NOT_FOUND |
| ORDER_ACCESS_DENIED | 403 REFUND_ACCESS_DENIED |
| TICKET_NOT_FOUND | 404 REFUND_TICKET_NOT_FOUND |
| TICKET_NOT_IN_ORDER | 409 REFUND_TICKET_NOT_IN_ORDER |
| TICKET_NOT_VALID | 409 REFUND_TICKET_NOT_VALID |
| REFUND_NOT_ALLOWED | 409 REFUND_NOT_ALLOWED |
| SESSION_ALREADY_STARTED | 409 REFUND_SESSION_STARTED |
| REFUND_WINDOW_CLOSED | 409 REFUND_WINDOW_CLOSED |
| DUPLICATE_REFUND_REQUEST，同票被另一申请占用 | 409 REFUND_ALREADY_REQUESTED |
| IDEMPOTENCY_CONFLICT | 409 REFUND_IDEMPOTENCY_CONFLICT |
| REFUND_AMOUNT_CALCULATION_ERROR | 500 REFUND_AMOUNT_CALCULATION_ERROR |

相同 requestId、相同载荷返回原申请，不属于重复处理错误。三类新增错误分支分别有 HTTP 集成测试，核对主申请、逐票明细和支付资源的完整快照不变。金额错误在测试读取已锁 Ticket 时注入无效价格，未修改数据库中的原成交价。

## 事务与锁

协调入口不持事务；受理使用独立 READ_COMMITTED 事务，依次锁 Order → 当前账号共享锁 → Activity 共享锁 → Session 共享锁 → Ticket 主键升序 → 新退款申请及明细。Ticket 的关联链在锁前后核对；锁票语句只锁 Ticket 行。该顺序与现有支付的 Order 优先顺序一致。

申请幂等唯一键冲突须先退出并回滚受理事务，再用新的事务读取原单并比较退款意图。申请主表与全部明细一起提交；Ticket、订单状态、Seat、Zone、限购计数在受理阶段均保持原值。

## 实现与验证

| 输出 | 文件 |
|---|---|
| 申请与逐票明细迁移 | [V5__create_ticket_refund_tables.sql](../src/main/resources/db/migration/V5__create_ticket_refund_tables.sql) |
| HTTP 契约 | [退款申请 API](api/ticket-refunds.md)、[TicketRefundController](../src/main/java/com/limitactivitysystem/refund/controller/TicketRefundController.java) |
| 认证身份、申请时间与冲突协调 | [TicketRefundService](../src/main/java/com/limitactivitysystem/refund/service/TicketRefundService.java) |
| 所有权、原子受理与幂等重放 | [TicketRefundApplicationService](../src/main/java/com/limitactivitysystem/refund/service/TicketRefundApplicationService.java) |
| Ticket → OrderItem → Order 查询 | [RefundTicketMapper](../src/main/java/com/limitactivitysystem/refund/mapper/RefundTicketMapper.java) |
| 申请与明细持久化 | [TicketRefundMapper](../src/main/java/com/limitactivitysystem/refund/mapper/TicketRefundMapper.java) |
| 逐票金额政策 | [OrdinaryTicketRefundPolicy](../src/main/java/com/limitactivitysystem/refund/service/OrdinaryTicketRefundPolicy.java) |
| 政策接口与完整结果 | [TicketRefundPolicy](../src/main/java/com/limitactivitysystem/refund/service/TicketRefundPolicy.java)、[TicketRefundDecision](../src/main/java/com/limitactivitysystem/refund/model/TicketRefundDecision.java) |
| 服务器时间源 | [TicketRefundTimeConfiguration](../src/main/java/com/limitactivitysystem/refund/config/TicketRefundTimeConfiguration.java) |
| 状态机 | [门票退款申请与逐票明细](state-machines.md#门票退款申请与逐票明细) |

[OrdinaryTicketRefundPolicyTest](../src/test/java/com/limitactivitysystem/refund/service/OrdinaryTicketRefundPolicyTest.java) 的 30 项测试覆盖 48h、30d、14d、10d、场次开始的精确时点与前后 1ms，以及完整政策结果、逐票 HALF_UP、活动开售时间示例和非法成交价。

[TicketRefundApplicationMySqlIntegrationTest](../src/test/java/com/limitactivitysystem/activity/integration/TicketRefundApplicationMySqlIntegrationTest.java) 的 57 项测试使用真实认证、Controller、Service、MyBatis 和随机隔离 MySQL 库。前置门票通过实际创建订单和支付 API 产生；测试替换专用 Clock，按需固定或推进时间。

| 场景 | 已验证结果 |
|---|---|
| 四张两种价格的票，仅申请其中两张 | 两条 PENDING 明细；10.01 按 15% 手续费退 8.51，20.03 退 17.03 |
| AT-09 受理：四张不同票价，只选 B、D | 实际支付 200、400、800、1200；只按 B=400、D=1200 保存退款 340、1020，持久化明细之和 1360，四张 Ticket 均仍 VALID |
| 开售窗口与场次开始的优先级 | 开售窗口可覆盖十天内拒绝；场次开始仍拒绝 |
| 刚支付、但活动开售已超过 48h | 按活动开售时间，仍进入普通阶梯 |
| Clock 具有亚毫秒精度 | requested_at 截断到毫秒，返回值与数据库快照一致 |
| 整单申请、发布者申请本人订单 | 正常受理，仍为 PENDING |
| 他人订单、本人其他订单的票、他人的票、被改写的关联链 | 拒绝，完整快照不变 |
| 缺少订单、Ticket 或明细，票已 REFUNDED，混入无效票，订单未支付 | 拒绝，不残留申请或部分明细 |
| 客户端提交服务端字段，包括 null | 400；只允许四个请求字段 |
| 请求缺少字段、空票集合、非法票 ID、重复 ID、越长或空白文本 | 400，原子拒绝 |
| 申请等待订单锁时跨过费率边界 | requested_at 只读取一次，保留申请时 15% 费率 |
| 受理后跨场次开始再重放 | 原申请时间与金额完全相同，不重新定价 |
| 当前区域价格被改变 | 仍使用 Ticket.paid_price |
| 相同请求键重放，票 ID 顺序不同 | 返回同一申请 |
| 相同请求键更换原因、订单或票集合 | 409 IDEMPOTENCY_CONFLICT |
| 同票并发相同或不同请求键、不同订单并发复用请求键 | 幂等重放或只受理一个，数据库无重复申请 |
| 主申请或首条明细真实 INSERT 后抛异常 | 主申请与全部明细回滚，支付资源不变 |
| 数据库状态、金额 CHECK 和同票生成唯一键 | 非法值被拒绝；FAILED 保留历史并释放同票占用 |
| 一般不允许受理、窗口关闭、金额计算错误 | 返回三个不同 code；分别为 409、409、500，全部无副作用 |

本次退款验证 87 项，加安全 44 项、免费预约 41 项回归，共 **172 项通过，0 失败、0 错误、0 跳过**。指定四票价、错误语义与最终矩阵的证据见 [verification/day10-final](verification/day10-final)。此前记录保留在 [day10-refund-policy](verification/day10-refund-policy) 和 [day10-refunds](verification/day10-refunds)。AT-09 当前验证到申请受理和逐票金额快照，实际退票仍属于 Day 11。

## Day 10 必测矩阵

表中 Policy 指 OrdinaryTicketRefundPolicyTest，Application 指 TicketRefundApplicationMySqlIntegrationTest。

| 必测场景 | 已验证结果 | 自动化用例 |
|---|---|---|
| 别人的 Order | 403 REFUND_ACCESS_DENIED | Application.`ownershipMustFollowTicketThroughOrderItemToTheCurrentBuyersOrder`，OTHER_BUYER |
| Ticket 不属于 Order | 409 REFUND_TICKET_NOT_IN_ORDER | 同上，OTHER_ORDER、FOREIGN_TICKET、WRONG_ORDER_CHAIN |
| 已 REFUNDED Ticket | 409 REFUND_TICKET_NOT_VALID | 同上，REFUNDED |
| 48h 免费窗口 | 按活动开售起点，在截止前全额 | Policy.`theFreeWindowEndsExactlyFortyEightHoursAfterActivityOpening` |
| 距开始 >30d | 100% | Policy.`exactDayBoundariesUseTheApplicationTime`，30d 前 1ms |
| 14d<距开始≤30d | 85% | 同上，30d 精确时刻、14d 前 1ms |
| 10d<距开始≤14d | 60% | 同上，14d 精确时刻、10d 前 1ms |
| 距开始≤10d，免费窗口已过 | 409 REFUND_WINDOW_CLOSED | 同上；Application.`eligibilityAndCalculationFailuresHaveDistinctHttpCodesAndNoSideEffects` |
| Session 已开始 | 409 REFUND_SESSION_STARTED，优先于免费窗口 | Policy.`startTimeOverridesTheFreeWindow`；Application.`aStartedSessionIsRejectedEvenDuringTheOpeningPromotion` |
| 不同票价部分退款 | B=400、D=1200，分别退 340、1020 | Application.`at09FourDifferentPricesRefundOnlyTheSelectedTicketsAtTheirActualPrices` |
| requestId 重复 | 返回原申请，只有一条主申请 | Application.`concurrentSameTicketApplicationsAreIdempotentOrOneIsRejected`，sameRequestId=true |
| 相同 requestId、不同载荷 | 409 REFUND_IDEMPOTENCY_CONFLICT | Application.`idempotencyKeyMustRetainTheEntireRefundIntent` |
| requestedAt 客户端伪造 | 400，即使传 null 也拒绝 | Application.`clientCannotSupplyServerOwnedFieldsEvenWithNull`，requestedAt |
| 申请成功后资源 | 主申请与明细 PENDING；全部支付资源快照不变 | Application.`partialRefundSnapshotsEachActualTicketPriceAndOnlyAcceptsPending` 及四票价实验 |

Day 10 只建立和受理合法申请。Ticket → REFUNDED、Seat → AVAILABLE、Zone 库存恢复、限购数量扣减及真正退款生效由 Day 11 实现。Redis、RabbitMQ、改期特殊退款和活动取消自动退款均留在各自后续阶段。

已有 DB_PASSWORD 且 MySQL 可连接时，在项目根目录复跑：

```powershell
	mvn '-Dtest=OrdinaryTicketRefundPolicyTest,TicketRefundApplicationMySqlIntegrationTest,SecurityAccessTest,FreeReservationMySqlIntegrationTest' test
```

仅跑本次新增测试时：

```powershell
	mvn '-Dtest=OrdinaryTicketRefundPolicyTest,TicketRefundApplicationMySqlIntegrationTest' test
```

MySQL 用例要求 DB_PASSWORD，没有凭据时会跳过；跳过不算验收通过。当前交付只受理申请，实际退款执行、失败重试和资源恢复留到 Day 11。
