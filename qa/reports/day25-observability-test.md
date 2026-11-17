# Day 25 可观测性验收报告

**结论：Day 25 必做项完成。** 执行日期为 2026-11-12，以下时间均为 UTC+08:00。Docker 全流程通过，46 项定向测试通过；两处脱敏后的旧断言修复后，全仓 1780 项中 1777 通过、0 失败、0 错误、3 项按约定跳过，BUILD SUCCESS、退出码 0。

## 实现范围

- 复用现有观测依赖，补齐业务结果与计时、显式 W3C 消息上下文传播和延迟任务 Span Link。
- Docker 结构化业务日志、受保护的 metrics/prometheus 管理端点、定时数据库指标快照。
- 双应用与 RabbitMQ exporter 采集、21 图表的 LAS Backend Overview、三条 Prometheus 告警。
- 真实业务、完整退款售后 Trace、库存/毒消息/Redis 连接故障、单实例停启、告警恢复及数据库对账脚本。

## 运行环境与源码

- Docker RunId：`20261112-130850-deca1c04`，独立项目 `lasday25-20261112-130850-deca1c04`。
- Docker 验收：13:08:50～13:14:24，退出码 0，`stage-All.json` 为 `passed=true`。
- 应用镜像：`las-backend:day25`，ID `sha256:0b4eac1eca7018fd8316e31b3a9ad696c95a6de223ec5c4701620f5e54b6056d`。
- 镜像对应源码快照：`59ff32a684af66d114185208b1d37f52d8adc574`。镜像内 388 个类和资源与 Maven 产物逐项一致；部署 JAR 的 SHA-256 为 `83ECB9F6EDA71EF3BCC05DA7162D1F3E31F4CF2C1ACF795396D792A36423CF18`。
- Docker Engine 29.9.3；容器 Java Temurin 23.0.2_7、MySQL 8.4.11、Redis 8.10.3、RabbitMQ 4.4.0-management、Prometheus 3.5.0、Grafana 12.1.1、Tempo 2.8.2。复用 Day 24 的固定镜像摘要和现有 Maven 依赖，未新增下载。
- 原生 Maven 使用 IDEA Maven、OpenJDK 23.0.1、缓存依赖和独立测试数据库/Redis。
- 最终全量被测源码快照：`4d849568594e3450eb47bca3e15001cb2f4ef376`，458 个源码/测试/部署文件与快照匹配。相对 Docker 快照仅修改两处测试断言，运行代码和部署配置完全相同。
- 原工作分支、HEAD `98aaa1d82a439fbd2efbb6505e2d3074a97150bc` 和已有暂存内容保留，快照另存于 `codex/day25-observability-snapshot`。

本轮没有停止 Redis/RabbitMQ 容器，没有停止 Day 24 环境，没有修改开发数据库，也没有删除命名数据卷。

## 实际验收

| 项目 | 当前结果 |
| --- | --- |
| 双实例与基础依赖 | 两个应用连接独立 MySQL、Redis、RabbitMQ；空库 Flyway V1～V18 成功 |
| Prometheus | app-a、app-b 和 RabbitMQ exporter 均 UP；应用有不同 instance 标签 |
| 业务指标 | 支付、三种退款政策、缓存、Outbox、消费重试/DLQ、取消批次及任务均有实际业务数据 |
| 数据库指标开销 | 独立线程每 15 秒刷新内存快照，SQL 超时 2 秒；抓取不查数据库，失败保留旧值并暴露快照年龄 |
| Grafana | 自动加载 LAS Backend Overview，21 个面板查询成功；实际打开页面检查图表和 Tempo Explore |
| HTTP → MQ → 售后 Trace | 14 个 Span，逐级父子关系已核对 |
| 混合订单 | 订单 6 的限定商品退款数量 1，普通商品退款数量 0 |
| 业务拒绝 | 门票缺货、商品缺货、Product 共享限购均返回 409；缺货 Span 没有技术错误状态 |
| 取消恢复因果链 | 实际取消 Item Span 通过 Span Link 关联原取消请求 |
| 告警 | 三条规则通过 promtool 校验，均实际进入 Firing 并恢复 |
| 管理边界 | app-a、app-b 的 metrics/prometheus 两个端点均验证内部未认证 401、已认证 200、业务端口即使用管理凭据仍为 403，共 12 项 |
| 日志 | 检查 82 条业务日志记录，字段齐全；对照本轮真实凭据，敏感值命中为 0。多阶段文件可能包含重复记录 |
| 数据库对账 | 14 份 SQL 全部执行：13 份不变量检查零差异，1 份消息诊断明细正常 |

准确指标名称、标签和计数边界见 [可观测性参考](../../docs/observability.md)。核心指标为 `ticket_payment_seconds_count{result=...}`、`product_payment_seconds_count{result=...}`、`ticket_refund_seconds_count{policy=...,result=...}`、`cache_db_fallback_total{cache=...}`、`outbox_pending`、`outbox_oldest_age_seconds`、`mq_consume_seconds_count{consumer=...,result=...}`、`rabbitmq_queue_messages{queue=~".*[.]dead"}` 和 `cancellation_batch{status=...}`。业务 ID 不进入标签，共享数据库 Gauge 使用 max，避免双实例重复相加。

## 实际 Trace

| 关联项 | 值 |
| --- | --- |
| 请求 | `POST /ticket-refunds/1/process` |
| requestId | `b9a6ab74-f9ee-4ec2-90ae-2d8442b2885b` |
| traceId | `292e339ae33d4a28990d77be18bda9b9` |
| 资格变化 eventId | `e798df1e-b5c2-4eb1-80d6-b75bf40a6c5f` |
| 持久化 traceparent | `00-292e339ae33d4a28990d77be18bda9b9-3c45befe386abf49-01` |
| 退款/任务/订单 | refundId=1、taskId=1、ProductOrder=6 |

Grafana Explore 实际显示 14 个 Span，链路时间跨度约 1.14 秒。主要步骤如下，父 Span 的时长包含子步骤，不能直接相加：

| Span | 持续时间 |
| --- | ---: |
| HTTP server | 65.31 ms |
| ticket.refund | 52.68 ms |
| outbox.publish：资格事件 | 5.81 ms |
| mq.consume：资格事件 | 191.30 ms |
| qualification.task.process | 158.02 ms |
| qualification.after_sale | 151.80 ms |
| product.aftersale | 133.80 ms |

同一 Trace 还包含退款通知分支。`trace-linkage-summary.json` 保存全部 Span 和父子关系，`structured-log-evidence.json` 保存关联日志。

取消请求 Trace 为 `0b59e35c5bda4df083118b02e48a6a71`。扫描执行 Trace `d02000da010a8deb11346fb07f5a68f5`、`d2e2529d1bc36ce8fbb54090f754d6af` 的 `cancellation.item.process` 均实际带有指向该请求的 Span Link，原始 Tempo 响应另行归档。

## 故障、诊断和恢复

### 库存不足

两个待支付订单竞争最后库存。门票返回 `409 / INSUFFICIENT_INVENTORY`，商品返回 `409 / PRODUCT_ORDER_OUT_OF_STOCK`；结果计入 `INSUFFICIENT_STOCK`，未误计技术故障或 HTTP 5xx。第三次商品购买被 Product 共享限购拒绝。

- 门票库存 Trace：`81a6f876ef664a6a85f2250c3c603624`。
- 商品库存 Trace：`9e75b94795964f86b37cf7956760bb81`。
- HTTP 响应、支付指标、业务拒绝日志及 Span 属性均已保存。

### 消费失败、DLQ 与重放

通知正文为空的测试事件 `6959a8e7-3a52-440e-bb35-6cef05587abc` 经 Outbox 发布。消费者失败 3 次，前两次确认重试发布，第三次进入实际死信队列。修复前没有成功的 consumer_process 或通知业务效果；修正测试载荷后，通过管理员重放，最终只产生一份通知效果和一条成功处理记录，再 ACK 原死信副本。

毒消息 Trace：`f0a6ce7c31414d59bf5012e7bfe51ee5`。失败 Span 记录错误类型，日志不保存原始异常正文。指标、实际队列深度、数据库结果和重放结果共同证明恢复，未通过清空队列掩盖失败。

### Redis 连接故障

13:13:15 仅把 app-a 的 Redis 连接端口配置为不可用端口，Redis 容器和 app-b 始终运行。app-a 的 readiness 返回 503/DOWN，app-b 保持就绪；活动和商品各读取 3 次，全部回源并返回 200，耗时分别为 62、110、20、23、26、24 ms。Prometheus 记录 activity/product 各 3 次回源。

故障期间重复门票支付、商品支付和退款处理，数据库快照前后均为 `0:1:0:1`，依次代表目标 SKU 库存、Product 有效购买数、票区剩余量和成功退款明细数。13:13:56 恢复基础配置，13:14:22 确认应用恢复、缓存再次命中。故障后完整不变量再次通过 SQL 对账。

### 三条告警时间线

Outbox 实验仅延后本轮事件的下次尝试时间，保持真实创建时间，让 oldest age 自然超过阈值，没有回填虚假时间。

| 告警 | 注入 | Firing | 定位 | 执行恢复 | 告警恢复 |
| --- | --- | --- | --- | --- | --- |
| LasInstanceDown | 13:10:06 停 app-a | 13:11:29 | 13:11:29：A DOWN/B UP | 13:12:02 启 app-a | 13:12:29 |
| LasOutboxOldestAge | 13:10:06 延后发布 | 13:11:59 | 13:11:59：事件仍待发布 | 13:12:02 恢复可发布时间 | 13:12:30 |
| LasDeadLetterMessages | 13:12:02 放行毒消息 | 13:12:45 | 13:12:45：重试耗尽、死信积压 | 13:12:50 修复重放、ACK 原副本 | 13:13:14 |

app-a 连续停止超过 1 分钟，app-b 保持 UP。最终三个 Target 均 UP，活动告警为 0。时间来自轮询观察，精度受 15 秒抓取/规则间隔影响。

## Java 回归与修复

```powershell
mvn -o -B -Dtest=BusinessTelemetryTest,PaymentTelemetryTest,RefundTelemetryTest,CacheMetricsTest test
```

定向测试实际 46 项，46 通过，0 失败、0 错误、0 跳过，BUILD SUCCESS，耗时 32.672 秒。新增测试覆盖 W3C 提取、MDC 恢复、错误脱敏、两种库存异常、事务提交失败、抓取不查数据库、刷新失败和延迟任务 Span Link。

全量回归由 `tests/concurrency/run-day23-tests.ps1 -FullSuite` 在独立原生测试资源运行。实际命令：

```powershell
mvn -o -B clean test -Dcache.redis.key-prefix=las:test:day23:20261112-132824-d3972ae4:
```

最终 RunId 为 `20261112-132824-d3972ae4`，运行时间 13:28:30～13:37:05，耗时约 8 分 35 秒。1780 项、1777 通过、失败 0、错误 0、跳过 3，BUILD SUCCESS、退出码 0。Surefire XML 与汇总一致，运行前后源码摘要一致；自己的临时数据库和 Redis 进程已清理。完整日志、XML 和前后摘要保存在 `qa/verification/day23/20261112-132824-d3972ae4/`，Day 25 目录另存最终索引。

| 关键测试类 | 总数 | 通过 | 跳过 |
| --- | ---: | ---: | ---: |
| ProductCatalogMySqlIntegrationTest | 127 | 126 | 1 |
| RabbitMqOutboxReliabilityMySqlIntegrationTest | 9 | 8 | 1 |
| SessionRescheduleMySqlIntegrationTest | 33 | 33 | 0 |
| CancellationMySqlIntegrationTest | 37 | 37 | 0 |
| BusinessTelemetryTest | 7 | 7 | 0 |

37 组取消回归对账记录也全部零差异。原生全仓日志另有 9 条本机 `localhost:4318` 未监听导致的 OTLP 导出连接错误；它们不是 JUnit 失败，也不能作为 Tempo 导出通过的证据。端到端导出结论来自独立 Docker 环境中实际查询到的 Trace，报告不宣称原生日志完全无异常。

三个预定跳过项分别为：

1. `ProductCatalogMySqlIntegrationTest.realRedisStopPreservesTradesAndRecoveryDrainsDurableInvalidation`：Redis 容器真实停机，留到 Day 28。
2. `RabbitMqOutboxReliabilityMySqlIntegrationTest.realBrokerStopRetainsCommittedOutboxAndRecoveryDeliversIt`：RabbitMQ 容器真实停机，留到 Day 28。
3. `Day21ReconciliationMySqlIntegrationTest.allSqlScriptsUseOneReadOnlySnapshotAndSaveEveryResultSet`：保留演示库的独立对账入口，本次隔离全仓不连接该库。Day 25 Docker 环境已另行执行全部 14 份 SQL，不能将该入口跳过解释为没有执行本日对账。

本轮发现并处理的问题：

1. 商品整单缺货使用独立的 `ProductOrderOutOfStockException`，初版观测只识别 `ProductException`，导致正常 409 被标记为技术失败。补齐映射和参数化测试，Docker 缺货验证通过。
2. JSON 时间字段重命名需要保留 `@timestamp` 键的绑定语义，改用带方括号的配置，真实日志已包含 `timestamp`。
3. 预注册零基线后，旧单测按“任意第一个 Counter”取值不可靠，改为明确名称及标签，没有删减业务断言。
4. 失败记录改存异常类型以避免泄露 SQL/载荷，旧商品/MQ 集成断言仍要求原异常文本。已改为断言 `SQLException` / `IllegalStateException`，保留真实 Publisher Return、未发布状态、重试、终态查询和恢复效果检查。首次全仓为 1780 项、1775 通过、2 失败、0 错误、3 跳过，退出 1；原始失败证据位于 `qa/verification/day23/20261112-131719-f6ce8e25/`。
5. 验收脚本补齐空 POST 的 JSON Content-Type，以及 PowerShell 对 problem+json 字节响应的解码。此前三次未通过的运行证据保留，报告采用最终完整成功运行。
6. 最后复核发现 Tempo 的 protobuf JSON 可用字符串枚举表示错误状态。PowerShell 验收断言补齐数字/字符串两种 ERROR 检查，并断言业务结果为 INSUFFICIENT_STOCK；使用两条真实库存 Trace 再验证通过，记录在 `stock-enum-validation.json`。这项修改在全仓结束后仅涉及验收脚本，Java 源码、Java 测试和运行配置未改变。

## 使用与证据

当前保留的独立环境入口：

- 应用 A：`http://127.0.0.1:10633`；应用 B：`http://127.0.0.1:10634`。
- Prometheus：`http://127.0.0.1:10635`。
- Grafana：`http://127.0.0.1:10636`，进入 LAS → LAS Backend Overview，或 Explore → Tempo。
- Tempo 查询 API：`http://127.0.0.1:10637`。

Grafana 用户名为 admin，密码取本轮证据目录 `.env` 的 `GRAFANA_PASSWORD`。不要将该文件或 `state.private.json` 提交或共享。Dashboard JSON、数据源和规则由 `deploy/observability/` 自动加载；步骤见 [验收指南](../guides/day25-observability-runbook.md)。

证据目录：`qa/verification/day25/20261112-130850-deca1c04/`。包含源码匹配、Targets、面板查询、Trace JSON、关联日志、故障时间线、Redis 恢复、管理认证和对账结果，已被 Git 忽略。Tempo 保留 24 小时，过期后仍可检查归档 JSON，不保证这些 Trace ID 永久可在线查询。

## 范围边界

- Redis/RabbitMQ 真实交易中途停机矩阵按约定留到 Day 28，不计本日通过项，也不据此宣布 M3 正式通过。
- 生产阈值尚未经过容量评估；采样默认 0.1，隔离验收使用 1.0。
- 未接入 Alertmanager 或外部通知，本日验证 Prometheus 告警和 Grafana 状态展示。
- 没有自动 JDBC/SQL Span；已验证明确创建的服务和消息 Span。
- 原生测试未连接 Tempo 导出端点，9 条连接错误已如实保留；若要在原生 IDEA 测试中也收集 Trace，需要另行配置可达的 OTLP 接收端点。Docker 观测验收不受此限制。

今日必做项没有未完成项。真实停机、生产告警校准、外部通知及原生测试 Trace 导出不在本轮通过范围内。
