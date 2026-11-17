# Day 20 持票资格联动验收

日期：2026-11-08  
状态：验收用例已补齐，真实 MySQL / RabbitMQ 执行与最终对账待运行；本报告不将未运行场景记为通过。

## 环境与运行结果

本环境没有配置 `DB_PASSWORD` 或 `RUN_DAY20_LINKAGE_TESTS`。本次复核时 Compose MySQL 端口 `127.0.0.1:3307`、RabbitMQ AMQP `5672` 和管理端口 `15672` 均不可用；本机 `3306` 有 TCP 响应，但没有测试数据库凭证。Docker、Podman、Nerdctl 均不可用。因此没有实际执行数据库迁移、Rabbit 发布/消费或 SQL 对账。

`ProductCatalogMySqlIntegrationTest` 的 Day 20 Rabbit 用例要求专用可丢弃 MySQL 与 RabbitMQ，环境变量 `RUN_DAY20_LINKAGE_TESTS=true` 才会启用。用例在消费前检查相关队列为空，避免读取其他工作负载的消息。

重试耗尽用例先注入售后写入失败，再调用重试作业直到 `attempt_count=5`。用例只把 `next_attempt_at` 提前到当前数据库时间来省去等待，不直接改写尝试次数。随后通过 ADMIN 查询并重放终态任务。

本机 Maven `test-compile` 的 JDK compiler plugin 返回了无源位置的 `unknown compilation problem`（终端显示“无法关闭编译器资源”）。直接 JDK 23 `javac -source 23 -target 23` 编译全部 265 个主源码和 `ProductCatalogMySqlIntegrationTest` 源码成功；这只确认源码可编译，不代表测试已运行，真实依赖环境仍不可用。

## 验收矩阵

| 场景 | 自动化证据 | 本次结果 |
|---|---|---|
| 退掉部分票，仍有同场次有效票 | `partialRefundWithAnotherValidTicketDoesNotStartQualificationAfterSale` | 未运行 |
| 退掉最后一张票，限定礼盒联动售后；同订单普通商品保留（AT-15） | `at15LastTicketRefundUsesRabbitOutboxAndOnlyRefundsQualifiedLineInMixedOrder` | 未运行 |
| ACTIVITY 商品因其他场次仍有票而保留 | `activityScopeUsesOtherSessionTicketWhileSessionScopeDoesNot` | 未运行 |
| SESSION 商品只看指定场次，其他场次有票仍触发联动 | 同上 | 未运行 |
| 未发货限定商品取消对应明细并模拟退款、恢复 SKU 与购买额度 | AT-15 用例及 `product_after_sale_invariants.sql` | 未运行 |
| 已发货限定商品先等待退回；收货后才模拟退款和回库 | `shippedQualifiedItemWaitsForReturnReceiptBeforeRefundAndRestock` | 未运行 |
| 普通公开商品不受持票联动影响 | AT-15 混合订单用例 | 未运行 |
| 重复 eventId 不重复产生售后 | AT-15 用例向 Rabbit 再投同一事件 ID | 未运行 |
| 不同 eventId 指向同一商品明细不重复退款/回库 | AT-15 用例投递第二个 event ID | 未运行 |
| 退票事件迟到，但买家已重新取得资格 | `delayedRefundEventRechecksEligibilityAfterAnotherTicketIsPurchased` | 未运行 |
| 商品支付与最后一张票退款只形成合法先后结果 | `ticketRefundThatGetsTheGuardFirstMakesLaterProductPaymentRecheckAndReject`、`productPaymentThatGetsTheGuardFirstLeavesTraceableSaleForLaterTicketRefund` | 未运行 |
| 商品发货与资格联动售后在 ProductOrder 锁下串行 | `productShipmentAndQualificationAfterSaleSerializeOnProductOrder` | 未运行 |
| 消费者售后失败不回滚 Ticket 退款；任务失败后可查询、受控重放 | `consumerFailureLeavesTicketRefundCommittedAndTerminalTaskCanBeQueriedAndReplayed` | 未运行 |
| RabbitMQ 不可用后 Outbox 仍可补发资格事件 | `qualificationOutboxSurvivesUnavailableRabbitEndpointAndPublishesAfterRecovery` | 未运行 |
| 任务重试耗尽后可查询并由 ADMIN 重放 | 同一任务失败与重放用例 | 未运行 |
| 退款数量、SKU、Product 额度与 Ticket 状态对账 | `ProductCatalogMySqlIntegrationTest` 每例 teardown、`RefundInvariantAssertions`、`product_after_sale_invariants.sql`、`qualification_linkage_invariants.sql` | 未运行 |
| Day 19 Confirm / Return、ACK 重投、有限重试与 DLQ | `RabbitMqOutboxReliabilityMySqlIntegrationTest`，见 [Day 19 报告](day19-mq-test.md) | 未运行；Day 19 仍未确认通过 |

## 对账

集成测试结束时运行退款、商品售后及资格任务不变量查询。资格任务检查包含：任务资格快照与明细一致、SUCCEEDED 明细已全部退款且没有预留数量、WAITING_RETURN 仍处于已发货或已收货状态、FAILED 任务有失败原因且尝试次数不超过五次。AT-15 用例额外检查 Ticket 与退款已成功、限定明细退款数量为 1、普通明细退款数量为 0、订单保持 `PARTIALLY_REFUNDED`、限定 SKU 和 Product 额度恢复、普通 SKU 和购买额度保持原状态。

在 `DB_PASSWORD`、MySQL URL 与 RabbitMQ 凭证已配置且 Rabbit 队列为空的隔离环境中运行：

```powershell
$env:RUN_DAY20_LINKAGE_TESTS = 'true'
mvn '-Dtest=ProductCatalogMySqlIntegrationTest' test
```

结束后需保存所有用例通过/失败/跳过计数，并执行：

```powershell
mysql --protocol=TCP -h 127.0.0.1 -P 3307 -u app_user -p limit_activity_system < tests/sql/refund_invariants.sql
mysql --protocol=TCP -h 127.0.0.1 -P 3307 -u app_user -p limit_activity_system < tests/sql/product_after_sale_invariants.sql
mysql --protocol=TCP -h 127.0.0.1 -P 3307 -u app_user -p limit_activity_system < tests/sql/qualification_linkage_invariants.sql
```

本次没有可保存的最终 SQL 结果集。只有三个脚本的 violation 查询都为空，并且测试报告中没有把跳过计作通过，才可将 Day 20 记为通过。
