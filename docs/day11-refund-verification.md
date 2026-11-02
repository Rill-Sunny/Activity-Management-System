# Day 11 退款验收

本次验收对应 schedule.md 的 AT-09、AT-10，以及第十三至二十四项要求。测试使用真实认证、下单、支付、退款受理和处理入口，在随机隔离的 MySQL 数据库中运行。模拟退款的生效证据是成功明细、金额和资源变更，不涉及外部资金渠道。

2026-10-31 最终回归共 **398 项通过，失败 0、错误 0、跳过 0**。其中退款处理 70 项、退款指标日志 18 项、支付指标日志 17 项、政策 30 项、Day 10 受理 57 项，其余为支付、关单及安全回归。十个测试类的计数见 [运行汇总](verification/day11-refunds/run-summary.json)。

## AT-09 只退四票中的 B 和 D

`at09RefundOnlyBAndDAtTheirFourActualPaidPrices` 实际支付四个区域的四张票。活动开售已超过 48h，场次在二十天后，手续费为 15%，退款比例为 85%。处理前只受理 B、D，处理后逐票结果如下：

| Ticket | 实际成交价 | 退款金额 | Ticket 状态 | Seat 状态 | 所属 Zone 恢复量 |
|---|---:|---:|---|---|---:|
| A | 200.00 | — | VALID | SOLD | 0 |
| B | 400.00 | 340.00 | REFUNDED | AVAILABLE | 1 |
| C | 800.00 | — | VALID | SOLD | 0 |
| D | 1200.00 | 1020.00 | REFUNDED | AVAILABLE | 1 |

数据库成功明细合计为 1360.00，订单为 `PARTIALLY_REFUNDED`，当前场次的 `valid_count` 从 4 降为 2。库存只恢复两张。证据见 [四票价处理结果](verification/day11-refunds/day11-at09-four-prices-succeeded.json)。

## AT-10 同一张票只生效一次

`uq_refund_ticket_claim` 保证同票最多存在一个 PENDING、PROCESSING 或 SUCCEEDED 明细。因此测试不删除约束来制造两个合法 PENDING 申请，而是覆盖真实入口及失败历史允许的竞争路径：

| 场景 | 实验与断言 |
|---|---|
| 相同 requestId | 两线程同时受理同票，取得相同 refundId，再同时处理。两个响应相同，额度扣减、库存恢复、Seat 释放、Ticket 退票和主申请成功各执行一次。 |
| 不同 requestId | 两线程同时受理同票，在两次受理结束后才处理。唯一占用使一方受理成功，另一方返回 409 REFUND_ALREADY_REQUESTED；成功方处理一次。 |
| 两个已有 requestId 同票竞争 | 第一笔技术失败并释放唯一占用后，第二笔受理同票。两个线程分别重试旧申请、处理新申请。旧申请重新取得占用时被拒绝，新申请成功。 |

每个场景均核对：原票只有一次 REFUNDED、Seat 只释放一次、Zone 只恢复一张、`valid_count` 只减一，成功明细只有一条，成功退款金额等于该票冻结报价。除最终资源快照外，相同及不同 requestId 的完整入口实验还记录成功条件写入次数。

证据：[同 requestId](verification/day11-refunds/day11-at10-same-request-id.json)、[不同 requestId](verification/day11-refunds/day11-at10-different-request-id.json)、[两个已有申请](verification/day11-refunds/day11-at10-competing-existing-requests.json)。

## 支付和退款按共同顺序取锁

`paymentAndRefundWaitOnQuotaInBothCommitOrders` 分别让支付、退款先持有额度及 Zone 锁，再启动另一方。通过 `performance_schema.data_lock_waits` 观察另一连接等待 `user_session_purchase.PRIMARY`，随后释放持有者，验证双方均成功。

SQL 追踪断言双方都先取得 Quota，再锁全部 Zone，最后操作 Seat。同层 Zone 和 Seat 按 ID 升序。最终额度与库存核对包括新支付的一张票，不能仅断言两个 HTTP 响应成功。协议见 [锁顺序](locking.md#ticket-refund-processing)。

证据：[退款先持锁](verification/day11-refunds/day11-payment-refund-refund-first.json)、[支付先持锁](verification/day11-refunds/day11-payment-refund-payment-first.json)。

## 七个位置分别验证部分退款和整单退款

`sevenFailurePointsRollBackPartialAndFullRefunds` 在实际 MyBatis 执行器注入未检查异常。每个位置分别处理两张、四张票，共十四个场景。

| 注入位置 | 时点 |
|---|---|
| valid_count 减少 | 条件 UPDATE 成功后 |
| Zone 库存恢复 | 第一个区域的汇总 UPDATE 成功后 |
| 第一个 Seat AVAILABLE | 第一条座位 UPDATE 成功后 |
| 第一个 Ticket REFUNDED | 第一条退票 UPDATE 成功后 |
| 所有 Ticket REFUNDED | 最后一条退票 UPDATE 成功后 |
| Order 汇总 | 更新 PARTIALLY_REFUNDED 或 REFUNDED 前 |
| Refund Request SUCCEEDED | 主申请成功 UPDATE 前 |

原子性实验直接调用处理事务的 Spring 代理，故障后比较八张表的全部有序行：ticket_order、ticket_order_item、ticket、session_zone、session_seat、user_session_purchase、ticket_refund_request、ticket_refund_item。所有内容均与处理前相同，包括时间、结果和状态。Ticket 均为 VALID，Seat 均为 SOLD，库存和限购未变，Order 为 PAID，退款仍为 PENDING。随后使用原 refundId 重试并验证成功。

十四份原始结果以 `day11-rollback-partial-` 和 `day11-rollback-full-` 命名，保存在 [验收证据目录](verification/day11-refunds)。示例：[部分退款在首票后失败](verification/day11-refunds/day11-rollback-partial-first-ticket-after.json)、[整单退款在主申请成功前失败](verification/day11-refunds/day11-rollback-full-request-success-before.json)。原有逐次写入故障和零影响行测试也继续运行。

## 技术失败在回滚之后独立记录

HTTP 入口调用事务外 `TicketRefundExecutionService`。数据库异常退出处理代理并完成回滚后，协调器才调用 `TicketRefundFailureService.record` 的独立短事务。

`databaseFailuresRollBackThenRecordFailedInANewTransactionAndRetryTheOriginalQuote` 在真实额度写入后注入 JDBC 异常，用 SQLState 和 MySQL 错误码经过实际 MyBatis 与 Spring 异常翻译：

| 故障 | 服务器异常类型 | HTTP 与 code |
|---|---|---|
| 连接中断，08006 | DataAccessResourceFailureException | 503 REFUND_TECHNICAL_FAILURE |
| 锁超时，HY000 / 1205 | CannotAcquireLockException | 503 REFUND_TECHNICAL_FAILURE |
| SQL 语法错误，42000 / 1064 | BadSqlGrammarException | 500 REFUND_TECHNICAL_FAILURE |

这些是故障注入，不会关闭 MySQL 服务。测试在失败记录的独立事务刚开始时，确认资源已恢复、主申请仍为 PENDING，随后才允许写入 FAILED。最终资源六表保持原值，主申请及全部明细为 FAILED，明细 failureReason 保存异常类型。向原 refundId 重试后成功，requestId、票集合、申请时间及报价均保留，失败原因清除。

业务拒绝沿用明确的 4xx code，并比较八表快照不变，不写技术失败。数据库异常保留服务器日志和原始原因，不改写为 REFUND_NOT_ALLOWED。

另有两个并发及故障实验：

- 暂停第一次处理的失败记录，让另一个处理先成功提交，再恢复失败记录。记录事务重新锁 Order、Request 并检查状态，不覆盖成功。
- 在失败记录的第一条明细 UPDATE 后再次抛异常。失败记录自身回滚，原始数据库异常继续抛出，记录异常作为 suppressed exception 保留。

证据：[连接异常](verification/day11-refunds/day11-technical-failure-connection.json)、[锁超时](verification/day11-refunds/day11-technical-failure-lock_timeout.json)、[SQL 异常](verification/day11-refunds/day11-technical-failure-unexpected_sql.json)、[延迟失败记录与成功竞争](verification/day11-refunds/day11-failure-recorder-success-race.json)。

## 退款座位可以再次出售

`refundedSeatCanBeResoldAndReplayingTheOldRefundCannotReleaseItAgain` 完成原票退款，再通过真实下单、支付为同一座位签发新票。数据库查询确认旧票 `active_seat_id = NULL`，新票 `active_seat_id = seat_id`，唯一索引 `uq_ticket_active_seat` 存在，该座位恰有一张 VALID 票。

新票为 VALID、Seat 为 SOLD。再次处理旧退款返回原成功结果，八表快照保持不变，不能释放新票座位或再次恢复额度、库存。证据见 [座位重售](verification/day11-refunds/day11-refunded-seat-resale.json)。

## 退款对账

[tests/sql/refund_invariants.sql](../tests/sql/refund_invariants.sql) 是只读检查，每个结果集都只返回异常行。正常结束时全部为空，脚本不修复数据。覆盖以下关系：

- 每个 SOLD Seat 对应一张 VALID Ticket，VALID Ticket 的 Seat、Zone、OrderItem、Order 关联合法。
- REFUNDED Ticket 有 refunded_at，且恰有一条成功退款明细。历史退票允许与重售后的新票共享 seat_id。
- valid_count 等于当前买家和场次的 VALID Ticket 数，同时检查缺失的计数行。
- PAID Zone 的 remains_amount 加 SOLD Seat 数等于 total_amount。
- 每个申请至少有一条明细，逐票报价与实际成交价一致，按申请汇总的退款金额等于冻价与费率公式之和。
- 主申请与全部明细状态一致，成功明细、原票及处理时间对应，订单状态符合剩余有效票数。

主表不重复存储 total_refund。查看申请报价总额与已成功金额时使用以下汇总；PENDING 或 FAILED 的报价不代表实际退款：

```sql
	SELECT r.refund_id, r.status, SUM(i.refund_amount) AS total_refund,
	       SUM(CASE WHEN i.status = 'SUCCEEDED' THEN i.refund_amount ELSE 0 END) AS succeeded_refund
	FROM ticket_refund_request r
	JOIN ticket_refund_item i ON i.refund_id = r.refund_id
	GROUP BY r.refund_id, r.status;
```

`RefundInvariantAssertions` 直接执行同一份 SQL，不另存一套测试查询。它要求所有工作事务结束，再用 REPEATABLE_READ 只读事务读取一致快照。处理集成测试每个用例结束都运行对账；原子回滚矩阵及技术失败测试还在回滚后、重试前执行检查。Day 10 的并发受理和故障注入用例也运行该脚本。

故意损坏数据的测试要求查出异常，正常测试要求零异常，不能把所有非空结果忽略。另有缺少明细、成功时间被清空、订单汇总被改坏三个反例，分别验证金额汇总、成功结果及订单状态检查能发现问题。故意破坏 Zone 关联的夹具按测试持有的 ID 清理，防止影响下一用例。逐用例审计见 [对账结果](verification/day11-refunds/day11-refund-reconciliation.json)。

最终处理测试审计共 70 个用例：53 个正常场景均为零异常行，17 个故意损坏场景均检出异常，未出现意外对账差异。

手工在 MySQL 客户端运行时，把全部查询放入一个只读快照：

```sql
	SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;
	START TRANSACTION READ ONLY;
	SOURCE tests/sql/refund_invariants.sql;
	COMMIT;
```

## 退款指标与日志

[RefundTelemetry](../src/main/java/com/limitactivitysystem/observability/RefundTelemetry.java) 使用与支付相同的 Micrometer 注册机制，在受理和处理的最终结果处记录一次。内部幂等冲突恢复不会重复计数。

| 指标 | 口径 |
|---|---|
| refund.requested | 进入受理 Service 的调用次数，包含重放及业务拒绝，不包含在认证或参数绑定时被拦截的 HTTP 请求。 |
| refund.success | 处理 Service 返回成功的调用次数，包含成功幂等重放。实际资源恢复次数和退款金额以成功明细及对账为准。 |
| refund.failure | 受理或处理的最终失败次数，按 operation、reason 分类。 |
| refund.duration | Service 调用耗时，按 APPLICATION、PROCESS 分类，包含锁等待、失败记录和必要的诊断读取。 |

失败 reason 仅有四种值：

| 分类 | 对应情况 |
|---|---|
| ALREADY_REFUNDED | REFUND_TICKET_NOT_VALID，当前 Ticket 状态只有 VALID 和 REFUNDED。 |
| INVALID_STATE | 申请状态、归属、申请窗口等稳定业务拒绝。REFUND_ALREADY_REQUESTED 也归此类，因为占用者可能仍在 PENDING。 |
| RESOURCE_INCONSISTENCY | 资源关联、计数、库存、Seat 状态、条件更新或服务端金额数据不一致。 |
| TECHNICAL_ERROR | 数据库异常、意外运行时异常或 Error。 |

所有指标只使用有限的 operation、reason 标签，不把用户、订单、票或请求 ID 放入标签。

业务日志包含 requestId、refundId、orderId、userId、ticketIds、operation、result、failureReason、failureCode、durationMs。requestId 来自 HTTP MDC，用于关联请求，清除控制字符并截断至 128 字符。ticketIds 排序去重，最多记录 50 个数字 ID。成功使用响应中的持久化 ID，失败仅尽力读取属于当前买家的申请元数据；数据库不可用时允许字段为空，不能为了日志掩盖原异常。

日志不记录退款原因、客户端业务 requestId、JWT、密码、散列、异常原文或完整请求。技术错误补充异常类型、SQLState 和厂商错误码，不输出 SQL 内容或原始异常消息。[RefundTelemetryTest](../src/test/java/com/limitactivitysystem/observability/RefundTelemetryTest.java) 验证计数、四类失败、重放、有限标签、控制字符及敏感内容过滤。

## Day 11 必测场景映射

| 场景 | 验收入口或测试分支 |
|---|---|
| 正常退一张，资源恢复一份 | successiveOneOneTwoRefundsDeriveSummaryFromRemainingValidTickets |
| 一单四张退两张，PARTIALLY_REFUNDED | at09RefundOnlyBAndDAtTheirFourActualPaidPrices |
| 一单全部退完，REFUNDED | successiveOneOneTwoRefundsDeriveSummaryFromRemainingValidTickets |
| 不同票价部分退款金额正确 | AT-09，400 退 340，1200 退 1020 |
| 同 requestId 重试不重复 | at10ConcurrentSameTicketApplicationsAndProcessingRestoreAndRefundOnlyOnce，true |
| 不同 requestId 同 Ticket 只成功一次 | 同测试 false，以及 at10FailedRequestAndANewRequestForTheSameTicketCannotBothSucceed |
| 重复处理已成功申请返回原结果 | repeatedProcessingAndApplicationReplayReturnTheOriginalResult |
| Ticket 已 REFUNDED 不重复恢复 | corruptOrPreviouslyRefundedResourcesCannotBeRestored，REFUNDED |
| Zone 恢复会超过 total 时拒绝 | 同测试，ZONE_OVERFLOW |
| Seat 不是 SOLD 时事务失败 | 同测试，SEAT_AVAILABLE |
| valid_count 不足时事务失败 | 同测试，QUOTA、MISSING_QUOTA |
| 任一故障完整回滚 | sevenFailurePointsRollBackPartialAndFullRefunds，十四个场景 |
| 退款后 Seat 再次出售 | refundedSeatCanBeResoldAndReplayingTheOldRefundCannotReleaseItAgain |
| 新 Ticket 的 UNIQUE 仍有效 | 同测试，检查新票 active_seat_id、唯一索引及同座有效票数 |
| 并发退款资源只恢复一次 | AT-10 及 concurrentProcessingOfSameOrDifferentRequestsRestoresEachTicketOnce |

本次只实现普通门票退款生效事务、模拟退款、对账和基础观测。RabbitMQ 异步退款、真实资金渠道、改期特殊退款、活动取消自动退款、商品退款、Redis 和赠票不在 Day 11 实现范围。

## 复跑

在项目根目录提供 DB_PASSWORD。MySQL 测试会创建随机隔离库并在结束时删除该库，不使用业务库作为夹具。

```powershell
	mvn '-Dtest=OrdinaryTicketRefundPolicyTest,TicketRefundApplicationMySqlIntegrationTest,TicketRefundProcessingMySqlIntegrationTest,RefundTelemetryTest,PaymentTelemetryTest,TicketPaymentMySqlIntegrationTest,TicketPaymentResourceMySqlIntegrationTest,TicketPaymentRequestContractTest,TicketOrderClosingMySqlIntegrationTest,SecurityAccessTest' test
```

单独复跑本次处理验收：

```powershell
	mvn '-Dtest=TicketRefundProcessingMySqlIntegrationTest' test
```

处理测试原始报告见 [TicketRefundProcessingMySqlIntegrationTest](verification/day11-refunds/com.limitactivitysystem.activity.integration.TicketRefundProcessingMySqlIntegrationTest.txt)。MySQL 测试跳过不算通过。
