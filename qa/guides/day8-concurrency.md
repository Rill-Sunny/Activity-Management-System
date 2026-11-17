# Day 8 小规模并发验证

检查时已有两线程的支付、取消和过期关闭竞争测试，但没有 20～50 并发入口。当前已补齐 [PowerShell 运行脚本](../../tests/concurrency/run-day8.ps1)、真实 HTTP 并发测试，以及重复取消和真实支付后取消的显式回归。

本次脚本验证 72 项，全部通过且没有跳过。运行步骤见[并发测试操作说明](../../tests/concurrency/README.md)，保存的结果见[本次报告](../verification/day8/concurrency/run-summary.json)。这次实验检查竞争下的数据一致性；正式性能压测仍在 D27。

## 并发人数如何落实到真实竞争

[TicketOrderSmallConcurrencyMySqlIntegrationTest](../../src/test/java/com/limitactivitysystem/activity/integration/TicketOrderSmallConcurrencyMySqlIntegrationTest.java) 使用 `RANDOM_PORT` 启动完整测试应用。Java `HttpClient` 通过本机 TCP 发起支付与取消，请求经过生产 Controller、JWT 认证和事务 Service。

自动关闭通过另一些工作线程调用生产的 `TicketOrderCloseService.closeExpiredOrder`。它们使用与扫描任务相同的关闭入口，每次独立事务。测试记录明确区分 HTTP 请求与关闭 Service 调用，应用没有为实验增加关闭 HTTP 接口。

20 并发表示 10 个支付操作和 10 个竞争操作；50 并发表示各 25 个。每组都针对同一笔只购买一张票的订单，使根订单成为共同竞争点。

客户端工作线程先进入就绪门闩，全部就绪后同时放行。独立协调事务先持有该订单的 `FOR UPDATE` 锁；观察线程通过 `performance_schema.data_lock_waits` 统计真实数据库等待者，再释放订单锁。因此，提交 50 个任务之外，还有实际的锁等待证据。

支付与取消、支付与到期关闭要求所有参与者进入数据库等待。提前触发关闭时，SQL 也可能直接因时间条件不满足而返回零行，所以该组至少要求所有支付参与者进入等待。本次六组均实际观察到完整的 20 或 50 个等待者。

测试连接池在首次启动前配置为 54 个连接，并在持有订单锁之前准备好本组人数加两个连接，供竞争者、协调者和观察者使用。原先六连接的共享测试默认值保留。连接池排队与数据库行锁等待是不同阶段；测试必须给观察线程留下连接，才能确认真实竞争。这个容量用于本次受控实验。

## 六组运行结果

| 竞争 | 操作总数 | 支付 / 另一操作 | 实际同时数据库等待者 | 最终状态 | Ticket / SOLD Seat / 限购 | 剩余库存 |
|---|---:|---:|---:|---|---|---:|
| 支付与用户取消 | 20 | 10 / 10 | 20 | CLOSED | 0 / 0 / 0 | 10 |
| 支付与用户取消 | 50 | 25 / 25 | 50 | PAID | 1 / 1 / 1 | 9 |
| 支付与提前关闭 | 20 | 10 / 10 | 20 | PAID | 1 / 1 / 1 | 9 |
| 支付与提前关闭 | 50 | 25 / 25 | 50 | PAID | 1 / 1 / 1 | 9 |
| 支付与到期关闭 | 20 | 10 / 10 | 20 | CLOSED | 0 / 0 / 0 | 10 |
| 支付与到期关闭 | 50 | 25 / 25 | 50 | CLOSED | 0 / 0 / 0 | 10 |

用户取消竞争的胜方可以随调度变化；测试允许 PAID 或 CLOSED，并按实际终态核对所有响应和资源。两笔确定先后顺序的既有测试继续覆盖支付先完成、关闭先完成的行为。

提前关闭组在订单未到期时调用关闭 Service，所有关闭结果为 `false`，支付最终成功。真正扫描未到期订单时不应产生候选，这由扫描回归单独验证。到期关闭组在开始竞争前把该测试订单设为已过期，支付只能返回过期或已关闭，恰好一次关闭返回 `true`。

每组完成后都重新查询数据库，并执行统一的 [payment_invariants.sql](../../tests/sql/payment_invariants.sql)：

- PAID：`paid_at < expires_at`，没有关闭原因，恰好一张 Ticket、一张 SOLD Seat，库存只扣一份，限购只加一份；重复支付响应中的 Ticket ID 相同。
- CLOSED：`paid_at` 为空，关闭原因为 `USER_CANCELLED` 或 `EXPIRED`，Ticket、SOLD Seat、限购均为零，库存仍为 10；恰好一次操作真正关闭订单。

合法的竞争失败响应是校验的一部分。只统计 HTTP 200 的数量会漏掉重复出票或资源未回滚；将所有 409 当成测试失败也会误判正常的状态竞争。

## Day 8 必测清单

| 场景 | 验证位置 | 断言 |
|---|---|---|
| 用户取消 PENDING | Closing 测试：`eitherBuyerRoleCancelsWithoutChangingAnyTicketResource` | CLOSED / USER_CANCELLED，资源不变 |
| 重复取消 | Closing：`repeatedHttpCancellationHasNoAdditionalEffect` | 第二次 409，整笔订单和资源快照不变 |
| PAID 后取消 | Closing：`cancellationAfterARealPaymentPreservesTicketsAndAllResources` | 真实支付后取消返回 409，Ticket 和资源不变 |
| 未过期扫描 | Closing：`scanningClosesOnlyOneBoundedBatchInExpiryOrder` | 新鲜订单保持 PENDING_PAYMENT |
| 精确到 expires_at | Closing：`at08UsesTheOrdersDeadlineAfterActivitySalesClose` | 前 1ms 不关闭，到点关闭 |
| 支付与用户取消 | SmallConcurrency 的 PAY_VS_CANCEL，加 Closing 的先后顺序测试 | 唯一合法终态，资源与终态一致 |
| 支付与自动关闭 | SmallConcurrency 两个 CLOSE 场景，加 Closing 的跨期回滚测试 | 提前不关、到期关闭，支付失败的资源完整回滚 |
| AT-08 | Closing：`at08UsesTheOrdersDeadlineAfterActivitySalesClose` | 20:00 停售后旧单可在自身期限内支付 |
| 人工死锁 | Retry：`reversedOrderLocksCauseARealDeadlockAndAscendingLocksBothCommit` | 真实 1213 / 40001，一个事务回滚 |
| 固定顺序对照 | 同一 Retry 测试的升序部分 | 两事务先小 ID 后大 ID，本场景均提交 |
| retry 一次后成功 | Retry：`aRealTimeoutAfterAllResourceWritesRollsBackThenANewAttemptCommits` | ROLLBACK → 新事务 COMMIT，资源只占一次 |
| retry 耗尽 | Retry：`threeRealTimeoutsReturn503AndLeaveEveryResourceAndTheOrderUnchanged` | 三次回滚，明确 503，完整数据库快照不变 |

Closing 指 [TicketOrderClosingMySqlIntegrationTest](../../src/test/java/com/limitactivitysystem/activity/integration/TicketOrderClosingMySqlIntegrationTest.java)，Retry 指 [PaymentRetryMySqlIntegrationTest](../../src/test/java/com/limitactivitysystem/activity/integration/PaymentRetryMySqlIntegrationTest.java)。运行脚本也执行 42 项 `PaymentRetryExecutorTest`，覆盖业务错误不重试、技术错误白名单、尝试次数上限和中断退避。

## PaymentRetryExecutor 与状态竞争的关系

`PaymentRetryExecutor.pay` 在事务外调用支付事务代理。仅明确的死锁或锁等待超时进入下一次完整事务，最多三次总尝试；每次都重新锁订单并读取当前状态。

另一请求先把订单取消后，支付取得锁读到 CLOSED，返回 `ORDER_NOT_PENDING`。这是终态业务拒绝，不进入技术重试。若等待过程中跨过订单期限，返回 `ORDER_EXPIRED`，同样不重试。支付先完成时，取消被拒绝，其他支付读取原成交结果。

并发用例因此同时检查最终状态、响应码、Ticket ID 和资源对账。重试层负责技术冲突恢复，订单状态机负责决定当前动作是否合法。两者分别由真实超时实验与状态竞争用例验证。

## 保存的证据

每组 JSON 在 `target/verification/day8-concurrency-*.json` 生成，包含参与人数、实际等待者、每个操作的结果、耗时和最终资源。当前六份保存副本与哈希记录位于 [verification/day8/concurrency](../verification/day8/concurrency)。AT-08、死锁、升序对照与重试原始记录见[原有实验说明](../reports/payment-retry-verification.md)。

这些请求耗时包含人为等待竞争者到齐的时间。单笔热点订单、同一买家、本机网络和测试连接池也限定了结论范围；本次通过不能用于估算生产吞吐或 P99 延迟。
