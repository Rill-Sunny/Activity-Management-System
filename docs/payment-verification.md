# 支付故障矩阵、对账与可观测性

## 七点跨表原子性矩阵

`TicketPaymentMySqlIntegrationTest.sevenAtomicityFailurePointsReconcileEveryTable` 创建一单两张票，以初始计数 0、库存 10、全部 Seat AVAILABLE、没有 Ticket 为基线。故障注入器只在测试配置注册，不进入生产代码。

| 故障点 | 异常前计数 | 异常前余量 | 异常前 SOLD Seat | 异常前 Ticket |
|---|---:|---:|---:|---:|
| 限购更新之后 | 2 | 10 | 0 | 0 |
| Zone 库存扣减之后 | 2 | 8 | 0 | 0 |
| 第一个 Seat SOLD 之后 | 2 | 8 | 1 | 0 |
| 全部 Seat 占用之后 | 2 | 8 | 2 | 0 |
| 第一个 Ticket INSERT 之后 | 2 | 8 | 2 | 1 |
| 所有 Ticket INSERT 之后 | 2 | 8 | 2 | 2 |
| Order → PAID 之前 | 2 | 8 | 2 | 2 |

七处的事务内 Order 均为 PENDING_PAYMENT。每次异常退出后，测试核对完整数据库快照，并分别断言订单仍待支付且 paid_at 为空、计数恢复 0、余量恢复 10、十个座位全部 AVAILABLE、Ticket 为 0。随后执行统一 SQL 对账。

观察结果写入 `target/verification/payment-fault-matrix-<POINT>.json`，包含异常前真实行状态与回滚后结果。原有逐阶段前后故障、最终状态更新失败与时间边界测试保留。

## 统一对账 SQL

[tests/sql/payment_invariants.sql](../tests/sql/payment_invariants.sql) 只读取当前数据库。每条查询返回违规记录，所有结果集为空才通过。它不选择固定数据库，也不自动修复数据。

| 检查 | 违规记录 |
|---|---|
| PAID Zone 库存 | `remains_amount + SOLD Seat 数 <> total_amount` |
| SOLD Seat 到 Ticket | SOLD Seat 没有恰好一张 VALID Ticket |
| VALID Ticket 到 Seat | Seat 不存在或非 SOLD，区域、明细、订单或场次关系不匹配，订单不允许有效票，或成交价不等于明细快照 |
| 账号场次限购 | 计数与该账号该场次 VALID Ticket 数不同，或有有效票却没有计数行 |
| PAID Order 总量 | Ticket 总数不等于明细 quantity 合计，或已支付订单没有明细 |
| PAID Order 每项数量 | 总数相同但 Ticket 分配到错误明细，造成单项数量不一致 |

GIFT_ALLOCATED Seat 不要求对应普通购票 Ticket，库存等式只针对 PAID Zone。PENDING_PAYMENT 订单没有 Ticket，以及没有有效票时的零计数行，均属于合法状态。

`PaymentInvariantAssertions` 在所有业务线程提交或回滚后，用新数据库读取执行这份文件。核心四组支付并发、缺货关闭竞争、锁等待跨期及七点故障矩阵都自动调用它。测试夹具中的已有计数由真实支付及 Ticket 构成；最后库存夹具也保持容量与座位一致。

故意构造坏数据的 SQL 反向测试验证八类损坏能够被检出。Day 6 单独资源组件实验和强制外部裸写 Seat 的防御测试并非完整成交数据，不套用“全库无违规”成功断言；这类测试保留各自的回滚检查。完整支付并发必须通过全库对账。

## 支付指标

`PaymentTelemetry` 使用已有 Actuator 的 Micrometer Registry，不增加 Prometheus 或 Grafana 部署。

| 名称 | 类型 | 语义 |
|---|---|---|
| `payment.success` | Counter | 支付 Service 成功返回的请求数，包括 PAID 重放 |
| `payment.failure` | Counter，`reason` 标签 | 最终向调用方抛出的失败次数，按固定原因分类 |
| `payment.duration` | Timer | 每次 Service 调用的总耗时，包含锁等待、支付事务和必要的失败关闭事务 |

一次调用只记录一个结果。缺货事务回滚后发现另一请求已经 PAID 并返回原 Ticket 时，记录一次成功；关闭本身发生技术故障时，记录 TECHNICAL_ERROR，不误报 INVENTORY。成功计数是请求计数，不等于新增已支付订单数。

失败分类为 `ORDER_EXPIRED`、`QUALIFICATION_DENIED`、`PURCHASE_LIMIT`、`INVENTORY`、`SEAT_CONFLICT`、`TECHNICAL_ERROR`。其他明确业务拒绝统一归入 `BUSINESS_REJECTED`，日志中的 failureCode 保留具体业务错误码。

标签值来自固定枚举。requestId、userId、orderId 不进入指标标签，避免按请求或账号不断产生新时间序列。未进入 Service 的认证与请求校验拒绝由已有 HTTP 指标及日志记录。

已认证请求可通过 `/actuator/metrics/payment.success`、`/actuator/metrics/payment.failure` 和 `/actuator/metrics/payment.duration` 查询，继续遵守现有管理端点认证规则。

## 支付日志

每次 Service 尝试记录 requestId、可信数字 userId、orderId、paymentResult、failureReason、failureCode、durationMs。HTTP requestId 来自已有服务端请求过滤器，控制字符会被清理；内部调用缺少 requestId 时记录 `-`，不伪造 traceId。

日志不接收请求正文、认证头、异常消息或异常对象，因此不会把其中的 JWT、密码、密码哈希或密钥带入支付日志。技术错误继续按原异常传播，不转换成缺货。

`PaymentTelemetryTest` 验证分类、计数、耗时、重复支付、关闭竞争后的最终结果及敏感消息不落日志。真实 MySQL + MockMvc 测试核对响应 X-Request-ID 与支付日志一致，并读取三个真实 Actuator 指标端点。Spring Security 过滤链始终启用。

复现支付及可观测性验收：

```powershell
mvn '-Dtest=TicketPaymentMySqlIntegrationTest,PaymentTelemetryTest,ObservabilityTest' test
```

测试需要已配置的 MySQL 连接和 DB_PASSWORD，使用独立随机 schema。测试结果以对应 Surefire 报告及本轮证据为准，不把跳过当作通过。

本轮支付 MySQL 类 84 个、支付指标单元测试 17 个、既有可观测性测试 7 个均通过。连同订单创建、安全及资源回归，共十五类 476 个用例，其中真实 MySQL 用例 243 个；失败、错误、跳过均为零。汇总为 `target/verification/day7-payment-atomicity-test-summary.json`，完整命令见[支付资源验收](payment-resources.md#自动化验证与证据)。
