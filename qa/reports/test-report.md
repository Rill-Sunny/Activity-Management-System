# Phase 1 Test Report

2026-11-01 执行 Day 13 阶段回归。本轮完成固定 fixture 的完整主链、AT-01～AT-10 与 AT-16、权限、真实 SQL 后故障回滚，以及五组一致性巡检。首次全量运行发现退款时间源缺陷，修复后重新验证。没有新增业务表或业务模块。

## Environment

| 项目 | 本次值 |
|---|---|
| Java | 23.0.1 |
| Spring Boot | 3.5.16 |
| MySQL | 8.4.11，InnoDB |
| 操作系统 | Windows，PowerShell |
| Commit | 基线 HEAD 为 `8535358bcd6142ab2678873faf5a955b3ddbce52`；执行对象包含未提交工作区修改，不能把该 HEAD 当作全部受测源码。 |
| 数据库 | 集成测试创建随机隔离库；主链真实 Flyway 迁移 V1、V3～V7。V2 含固定 USE，不在隔离测试中执行。 |
| 身份链路 | MockMvc 启用真实 Security。主链的五个账号全部经登录 API 获取 JWT。 |

运行环境与迁移证据见 [environment.json](../verification/day13-phase1/day13-environment.json)。结果仅代表本地自动化与数据库并发实验，不含压测、真实支付渠道或生产部署验证。

## Runs

首次基线执行 1509 项：失败 12、错误 0、跳过 0。失败集中于刚支付后退款受理，业务错误为 REFUND_NOT_ALLOWED。修复后的退款与主链专项执行 142 项：失败、错误、跳过均为零。最终全项目计数、各测试类结果与源码摘要保存在 [run-summary.json](../verification/day13-phase1/run-summary.json)。

最终全项目运行共 **54 个测试类、1529 项，全部通过，失败 0、错误 0、跳过 0**。其中新增主链及故障/权限/诊断测试 14 项、时间源回归 3 项、AT-08 具体时刻测试 3 项。主链 14 个用例提交后的五组巡检均无异常；诊断测试中的故意损坏另有明确检出断言，恢复后再运行正常对账。

复跑命令如下，DB_PASSWORD 必须已经配置，MySQL 测试跳过不算通过：

```powershell
	mvn test
```

主链单独复跑：

```powershell
	mvn '-Dtest=Phase1JourneyMySqlIntegrationTest' test
```

## Fixed fixture and complete journey

[phase1.json](../../tests/fixtures/phase1.json) 固定两个发布者、三个普通账号、一个 PAID 与一个 FREE 活动。PAID 有 SELECT_SEAT 和 AUTO_ASSIGN 两场及独立 PAID/GIFT Zone，FREE 有两场。时间相对数据库基准生成，所有资源 ID 从创建结果读取，不依赖已有库内容。

发布者账号由测试种子赋权，普通账号通过注册创建。活动、场次、区域、座位、发布、白名单和后续业务经过实际 API。退款前后的票价、状态、座位与限购均读取数据库断言。过期场景只在测试中调整 created_at，再调用现有过期关闭 Service。

| 阶段 | 实测数据库结果 |
|---|---|
| 四票支付 | 原成交价为 200、400、800、1200；四张有效票及四份限购、库存、座位占用一起提交。 |
| 受理部分退款 | 只受理 B、D，资源尚未恢复。 |
| 处理原退款 | 退款 340、1020；A/C VALID、B/D REFUNDED，订单 PARTIALLY_REFUNDED，只释放两席。 |
| 赠票分配 | 两个 GIFT Seat 分配并保留操作明细；没有新增领取人或普通 Ticket。 |
| 自动配座 | 另一个账号支付两张，座位全部属于对应 PAID Zone。 |
| 取消与过期 | 待支付订单关闭，不释放从未占用的资源；重复过期关闭无效果。 |
| 免费预约 | A 可跨两场预约，同场重复被拒绝；取消后 A、B 按公开规则重新参与，旧记录保留。 |
| 最终状态 | 五个账号、六张历史 Ticket，其中四张 VALID、两张 REFUNDED；三个 ACTIVE 预约。所有阶段五组巡检均无异常。 |

固定世界及构建步骤见 [fixture 说明](../../tests/fixtures/README.md)。主链证据见 [day13-full-journey.json](../verification/day13-phase1/day13-full-journey.json)。

## AT-01–AT-10 and AT-16

以下 Result 依据最终 Surefire 报告与本次 JSON 证据。精确时间单元测试与真实 MySQL 集成测试在 Evidence 中分别标注。

| AT | Expected | Actual | Evidence | Result |
|---|---|---|---|---|
| AT-01 | 同活动跨场次可分别预约，同用户同场次 ACTIVE 至多一条。 | A 预约两场成功，重复首场拒绝，数据库 ACTIVE 唯一。 | `FreeReservationMySqlIntegrationTest.at01ThreeSessionsAllowTwoBookingsButRejectDuplicateActiveReservations`；固定主链。 | PASS |
| AT-02 | 最后一份免费名额不超额。 | 二十个不同账号并发，恰好一个成功，名额与 ACTIVE 对账一致。 | `FreeReservationMySqlIntegrationTest.at02TwentyDifferentAccountsPostingForTheLastSlotHaveExactlyOneWinner`。 | PASS |
| AT-03 | 取消只恢复一次，重新预约无旧用户优先权。 | 保留取消历史，旧取消不能释放新预约名额；分别验证原用户和其他用户抢得释放名额。 | 同类 `at03CancellationAndRebookingKeepHistoryAndAnOldCancelCannotReleaseTheNewQuota`、`at03ReleasedSlotIsOpenToBothFormerOwnerAndOtherAccount`。 | PASS |
| AT-04 | 未配置、继承、覆盖、显式空名单语义不同。 | 六规则矩阵及空场次覆盖均通过；删除成员后新参与和支付复查当前资格，已获权益保留。 | `QualificationEntryPointMySqlIntegrationTest.bothBusinessEntriesUseTheSixQualificationRules`；`WhitelistChangeMySqlIntegrationTest`。 | PASS |
| AT-05 | 限购 4，两单各 3 并发支付至多成功一单。 | 状态为 200/409，有效票和 valid_count 均为 3，库存只扣 3。 | `Phase1JourneyMySqlIntegrationTest.at05TwoThreeTicketOrdersCannotExceedTheAccountLimitOfFour`；[并发证据](../verification/day13-phase1/day13-at05-quota-race.json)。 | PASS |
| AT-06 | 公开与 GIFT 双向隔离，无赠票领取人绑定。 | SELECT_SEAT、支付复查、AUTO_ASSIGN 均不出售 GIFT；GIFT 不借用 PAID；无新增账号、付费 Ticket 或购买计数。 | `GiftAllocationMySqlIntegrationTest`；[双向隔离](../verification/day13-phase1/day12-at06-paid-gift-isolation.json)、[无领取人](../verification/day13-phase1/day12-at06-no-receiver-binding.json)。 | PASS |
| AT-07 | 最后一张付费票只有一单可成功，无局部扣减。 | 两账号竞争一席，200/409，一张 VALID Ticket，库存零。 | `Phase1JourneyMySqlIntegrationTest.at07TwoPaymentsCompeteForOneRealPaidSeat`；[最后库存](../verification/day13-phase1/day13-at07-last-stock.json)。 | PASS |
| AT-08 | 19:58 创建、20:00 停售、20:13 过期；20:05 可付，20:13 不可付。 | 20:05 与 20:12:59.999 通过，20:13 拒绝；真实数据库另验证停售后有效旧单仍可支付，过期和场次开始均受最终时间检查保护。 | 精确时间：`TicketPaymentTimeValidationTest.at08The1958OrderRemainsPayableAfter2000ButNotAt2013`。MySQL：`TicketPaymentMySqlIntegrationTest.aValidOrderCreatedBeforeCloseCanReachResourceAllocationAfterTicketSalesClose` 及期限矩阵。 | PASS |
| AT-09 | 四票不同成交价，仅退 B/D；不取订单平均价。 | 400→340、1200→1020，A/C 有效、B/D 已退，订单 PARTIALLY_REFUNDED；资源恢复两份。 | 固定主链；`TicketRefundProcessingMySqlIntegrationTest.at09RefundOnlyBAndDAtTheirFourActualPaidPrices`。 | PASS |
| AT-10 | 同键、不同键、并发退款只生效一次。 | 重放原结果；同票申请及处理竞争不重复退款，不重复释放座位、库存与限购；退款后可重售。 | 同类 `at10ConcurrentSameTicketApplicationsAndProcessingRestoreAndRefundOnlyOnce`、`at10FailedRequestAndANewRequestForTheSameTicketCannotBothSucceed`、`refundedSeatCanBeResoldAndReplayingTheOldRefundCannotReleaseItAgain`。 | PASS |
| AT-16 | 发布后冻结价格、容量、模式、限购、座位与普通时间编辑，名单仍可维护。 | 普通编辑拒绝且数据库字段不变，不能新增 Seat/Session/Zone；名单管理仍按当前归属执行。 | `ActivityPublishingMySqlIntegrationTest.ordinaryEditsLeaveEveryPublishedCoreFieldUnchanged`、`publishedActivityRejectsAdditionalSeatsInTheDatabase`；`WhitelistManagementMySqlIntegrationTest`。 | PASS |

## Permissions and error semantics

固定 fixture 的权限矩阵验证其他用户不能读取、支付或取消原订单；发布者 B 与普通 USER 均不能配置 A 的活动、场次、区域、座位、白名单及赠票。禁用账号的既有 JWT 也无法继续操作。退款、预约取消和赠票历史的归属另由各模块集成测试覆盖。

交易客户端伪造 userId、publisherId 被拒绝，身份继续来自 CurrentUserProvider。白名单增加成员的 userId 是被管理的目标账号，其操作人仍来自认证上下文，因此该字段是既定业务输入，不属于身份绕过。

| 语义 | 现有代码 |
|---|---|
| 订单到期、状态非法 | ORDER_EXPIRED、ORDER_NOT_PENDING。 |
| 票务额度、库存、座位 | PURCHASE_LIMIT_EXCEEDED、INSUFFICIENT_INVENTORY、SEAT_NOT_AVAILABLE。 |
| 当前名单资格 | QUALIFICATION_DENIED。 |
| 免费名额、同场有效预约 | RESERVATION_QUOTA_EXHAUSTED、RESERVATION_ALREADY_ACTIVE。 |
| 退款时间或票状态 | REFUND_NOT_ALLOWED、REFUND_TICKET_NOT_VALID；重复已受理申请可返回 REFUND_ALREADY_REQUESTED。 |
| GIFT 缺货、资源不一致 | GIFT_INSUFFICIENT_INVENTORY、GIFT_RESOURCE_CONFLICT。 |

这些是现有公开错误码，保留模块边界，没有为示例中的近义命名批量改接口。HTTP 认证时发现禁用账号返回 401，事务内复查发现账号失效返回领域 403，属于不同检查阶段。数据库故障保留技术异常，不能转换为资格拒绝或缺货。

## Invariants and failure matrix

主链每个提交阶段、权限拒绝后和九个故障实验后，均在同一个只读 REPEATABLE_READ 快照执行以下五组 SQL。没有修复型 UPDATE，没有对正在执行的事务读取混合时点结果。

| SQL | 检查范围 |
|---|---|
| [invariants.sql](../../tests/sql/invariants.sql) | 有效 Seat 唯一、限购范围、已发布实际座位与规划容量、订单金额、原签票数量、未支付单无票。 |
| [orphans.sql](../../tests/sql/orphans.sql) | Activity、Session、Zone、Seat、Order、Item、Ticket、用户计数、名单、预约、退款及赠票的逻辑父记录。保留的历史也必须有效引用。 |
| [reservation_invariants.sql](../../tests/sql/reservation_invariants.sql) | FREE 归属、同账号同场 ACTIVE 唯一、quota 剩余加 ACTIVE 等于 capacity。 |
| [refund_invariants.sql](../../tests/sql/refund_invariants.sql) | SOLD↔VALID、限购等于有效票、PAID 库存、退款价格公式与状态、订单汇总。 |
| [gift_invariants.sql](../../tests/sql/gift_invariants.sql) | PAID/GIFT 容量、Session 规划上限、分池、逐座历史和普通 Ticket 隔离。 |

新增统一故障矩阵在实际 Mapper SQL 影响一行后抛出数据库技术异常：

| 模块 | 注入位置 | 结果 |
|---|---|---|
| 支付 | 增加额度、扣 Zone、占 Seat、INSERT Ticket 后 | 整笔回滚，资源与历史快照不变，五组巡检无异常。 |
| 退款 | 减额度、恢复 Zone、释放 Seat 后 | 资源回滚，独立失败事务可记录 FAILED；五组巡检无异常。 |
| 预约 | 扣名额后 | 名额及预约回滚，包含首次 quota 初始化，五组巡检无异常。 |
| GIFT | 首个 Seat 占用后 | Seat、库存和操作历史回滚，五组巡检无异常。 |

支付与预约的未分类数据库异常由 MockMvc 作为 ServletException 抛出，测试核对 SQLException 根因与回滚；退款和 GIFT 由现有 Advice 返回技术 500。报告不将前者伪称为已验证的统一 JSON 错误响应。更完整的原模块故障矩阵也随全量测试重跑。

## Lock order audit

本次通过 MyBatis 拦截器保存实际执行的 Mapper ID、带占位符的 SQL 和 Zone/Seat ID，并断言全部 Zone 在首个 Seat 之前、各层 ID 升序；不记录 SQL 参数中的账号凭据或 token。

| 路径 | 代码与实际追踪一致的顺序 |
|---|---|
| Payment | Order → 当前资格共享锁 → Quota → 全部 Zone ASC → 全部 Seat ASC → Ticket INSERT → 原 Order UPDATE。 |
| Refund | Order → Refund/Item → Quota → 全部 Zone ASC → 全部 Seat ASC → Ticket ASC → 原 Order/Refund 汇总。 |
| Gift | 当前账号及 Activity/Session 共享锁 → GIFT Zone → Seat ASC → Operation → Items。 |
| Reservation | 当前账号及 Activity/Session 共享锁 → Session Quota → Reservation；取消先普通发现作用域，再按相同顺序取写锁。 |

[day13-lock-traces.json](../verification/day13-phase1/day13-lock-traces.json) 保存本次主链追踪。跨订单支付/退款共同 Quota 的真实等待实验、GIFT Zone 等待和预约名额等待仍由既有并发用例验证。没有发现业务路径在持 Seat 锁后再获取新 Zone 锁。[locking.md](../../docs/locking.md) 与实际 SQL 对照后保留统一顺序和整事务重试要求。

## Bugs Found

### BUG-003：刚支付后退款被误判早于出票

Scenario：首次全量基线出现 12 个退款测试失败，正常申请返回 409 REFUND_NOT_ALLOWED；前一天的全绿未能排除时间相关偶发错误。

Cause：requestedAt 来自 JVM Clock.system，issued_at 来自 MySQL CURRENT_TIMESTAMP(3)。两套时间源有微小偏差时，程序中的实际先后顺序不能保证两个时间戳同样有序。非暂停日志断点捕获到 requestedAt=`2026-11-01T15:01:41.476`、issuedAt=`2026-11-01T15:01:41.478`，误差 2ms，进入了“申请早于出票”的拒绝分支。证据见 [退款日志断点](../verification/day13-phase1/refund-clock-debugger.json)。

Fix：生产 ticketRefundClock 改为 DatabaseRefundClock，复用 DatabaseTimeMapper，与出票使用同一数据库时间源。仍在业务锁等待前只读一次；不删除时间合法性校验，不增加容忍窗口，不重新定价已受理申请。退款测试的非固定时钟也改用数据库源，精确政策边界仍可使用 Fixed Clock。

Affected：普通门票退款受理时间与时间配置。支付、出票、资源处理顺序、表结构不变。

Regression：新增 TicketRefundTimeConfigurationTest 三项，验证数据库时间独立于 JVM 当前时间、时区转换保持同一 instant、每次新尝试读取及数据库失败传播；重跑申请 57 项、处理 70 项、完整主链、全部政策边界及最终全项目测试。等待锁前快照及跨场次开始仍可处理的旧测试继续通过。修复专项 142 项全部通过。

### BUG-001：零计数孤儿引用未被旧巡检检出

Scenario：在隔离测试中插入 user_session_purchase，user_id 不存在、session_id 存在、valid_count=0。原退款、预约、赠票脚本均返回零异常。

Cause：旧计数对账只比较有效票数量，没有检查计数所属用户是否存在。没有物理 FK 时，零等于零会掩盖引用损坏。

Fix：新增 orphans.sql，检查计数和其他业务历史的完整逻辑引用。该缺陷属于验收工具，不声称应用 API 产生了孤儿。

Regression：`orphanAndLimitAuditRegressionsExposeThePreviouslyMissedConditions` 验证旧脚本漏检、新脚本返回 COUNTER_USER_SESSION，清理造错记录后五组巡检全部无异常。证据见 [孤儿漏检](../verification/day13-phase1/day13-bug001-orphan-counter.json)。

### BUG-002：计数正确但超过限购仍未被旧巡检检出

Scenario：先合法支付四张，再仅在隔离测试中把 max_tickets_per_user 调为二，保持 valid_count=4、有效票=4。旧三组脚本仍无异常。

Cause：旧脚本验证“计数等于有效票数”，遗漏“计数不超过配置限购”这一独立条件。

Fix：invariants.sql 增加 PURCHASE_LIMIT，保留计数一致性原检查。没有将故意损坏夹具描述为正常业务越购。

Regression：同一诊断回归用例要求新脚本检出 4 > 2，恢复配置后正常对账零差异。证据见 [限购漏检](../verification/day13-phase1/day13-bug002-purchase-limit.json)。

## Scope

Day 13 第 13、14 项的连续 Demo 说明见 [demo-phase1.md](../../docs/demo-phase1.md)。在上述全量回归之后，新增并单独执行 `demoABCRunsPaidFreeAndGiftContinuouslyAgainstCommittedDatabaseState`，按 PAID → FREE → GIFT 连续调用，补验公开活动查询、退款座位恢复、重约新记录及两种状态的 GIFT Seat 均不能公开购买。专用测试 1 项通过、无失败/错误/跳过，九次五组 SQL 对账均零异常。该次执行单独保存在 [Demo 汇总](../verification/phase1-demo/run-summary.json)，未计入上文历史全量 1529 项。

本轮没有新增业务表、API 或业务模块。未进入 Product、SKU、Redis、RabbitMQ、改期或活动取消。GIFT 仍使用 Day 12 的 ALLOCATE-only 语义；RELEASE 与领取人业务没有扩展。
