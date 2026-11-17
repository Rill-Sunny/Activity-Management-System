# Day 22 场次改期验证报告

执行日期：2026-11-11。用户授权本轮直接运行现有 Maven、Spring 和本机 MySQL、Redis、RabbitMQ，并创建、清理隔离测试资源。未安装新框架、依赖或系统服务。

**最终结论：Day 22 本次任务已完成。AT-16～AT-18、T01～T14 及要求的数据库/MQ 故障场景通过。全仓 1733 项，1730 通过，0 失败，0 错误，3 项约定范围外跳过，BUILD SUCCESS，实际退出码 0。**

## 交付范围

F11 契约先于 V17 迁移落地，见 [设计契约](../../docs/day22-reschedule-design.md)。新增改期事件、受影响 Ticket 快照、按事件响应及跨订单特殊退款受理记录。改期通知和窗口消息使用真实 RabbitMQ，退款执行复用原有资源恢复和持票资格变化 Outbox。

接口见 [场次改期 API](../../docs/api/session-reschedules.md)。没有提前实现 Day 23 取消批次；取消门禁竞争由独立测试事务模拟。

## 已执行专项结果

| 轮次 | 总数 | 失败 | 错误 | 跳过 | 实际退出码 |
|---|---:|---:|---:|---:|---:|
| 首轮 20261111-075705-c91594d3 | 32 | 1 | 0 | 0 | 1 |
| 扩展复跑 20261111-080306-8691dac0 | 40 | 0 | 0 | 0 | 0 |
| 首次全仓 20261111-081737-1444215e | 1731 | 1 | 0 | 4 | 1 |
| 最终全仓 20261111-082748-cbb5ac86 | 1733 | 0 | 0 | 3 | 0 |

扩展复跑包括 31 项真实 MySQL 集成、7 项时间政策测试及 2 项 Rabbit 拓扑隔离测试，Maven BUILD SUCCESS，耗时 2 分 24 秒。测试执行前后没有修改系统时钟。

首轮唯一断言失败：注入 Session 更新失败后，异常已被接口转换为 503，原用例误用“应抛出异常”的断言。修正为检查受控响应，仍检查事务内曾有事件、回滚后事件与快照为零、场次时间保持原值。没有放宽回滚断言或增加数据库锁超时。

另修正了测试运行脚本的库名替换：初版误匹配时区参数中的斜杠。该失败发生在测试执行前，未计为用例结果。当前脚本单独处理 JDBC 地址和查询参数。

首次全仓唯一失败是旧退款 Schema 用例固定断言 33 张表，V17 新增 5 张表后实际为 38 张，已更新数量并保留零物理外键、金额和状态约束断言。脚本还补开了首次全仓遗漏的 RUN_DAY21_TRACE_TESTS。专项审查补强了批量保留的“先锁齐 Ticket，再写 Response”顺序，以及响应来源不能为空的 CHECK，新增两个真实 MySQL 约束用例，随后再次全仓复测。

## 验收映射

| 验收 | 自动化覆盖与结果 |
|---|---|
| AT-16、T02 | 普通 PATCH 拒绝；专用改期成功，Ticket、Seat 和活动冻结字段保持不变 |
| AT-17 | 7×24h 与新开始取较早者；两种官方示例及数据库公告时间通过 |
| AT-18、T11 | 主动确认和系统保留字段分开，重复处理幂等，退款票不重新有效 |
| T01 | 新开始大于等于结束、活动窗口不符、缺少 UTC 偏移均拒绝 |
| T03、T04 | 公告前后的付款分别进入与排除快照；两种并发顺序有真实 MySQL 等待证据 |
| T05、T06 | 截止前 1ms 受理；恰好截止及之后 1ms 拒绝 |
| T07、T08 | 确认后可特殊退款；实际数据库时间跨越截止后执行仍按原报价全额退款 |
| T09 | 确认与退款重放不修改首次记录或重复恢复资源 |
| T10 | 多次事件、快照、响应、窗口独立；旧事件仍可依法受理，同票不重复退款 |
| T12 | 普通与特殊退款并发仅一个受理；全部资源对账为零差异 |
| T13 | 特殊窗口结束后仍可走合法普通退款 |
| T14 | 非发布者、非所属发布者、跨用户、未认证及伪造 operatorId 被拒绝 |
| 跨订单 | 同一特殊请求产生多个原有退款申请；后一个订单冲突时整批不受理 |
| 生命周期并发 | 公告与普通退款两种顺序通过；取消门禁先提交后改期拒绝，无反向逐单锁 |

## 数据库与 MQ 证据

每个集成用例结束后，在同一只读 REPEATABLE READ 快照执行 `tests/sql` 的 13 个脚本。消息汇总保留作诊断，其余不变量结果正常为零行。`target/day22/reconciliation.json` 保存逐例差异、事件、响应及退款记录。

隔离库实际执行全部当前迁移（排除含固定 USE 的 V2 演示数据），共有 38 张应用表、0 个物理外键。通过 SHOW CREATE TABLE 更新 `database/schema_snapshot.sql`，并在运行证据保存同一份 schema.sql。

故障注入验证事件插入后 Session 更新失败整单回滚，证据为 `rollback.json`。等待图保存在 `cutoff-*.json`、`refund-cutoff-*.json`、`cancel-gate.json`，包含实际连接和 InnoDB 锁等待信息。

MQ 故障实验使用本机未监听端口让本次发布连接真实失败，既有 Broker 保持运行。已提交改期与 Outbox 保留，恢复到真实 Broker 后 Relay 补发，真实 Listener 提交通知与 consumer_process。故障前后状态保存在 `mq-unavailable.json` 和 `mq-recovered.json`。这证明测试连接不可用时的业务恢复，不等同于物理关闭 RabbitMQ 服务。

边界 ±1ms 用可注入 Clock 构造精确时间；延迟退款、实际窗口消息、扫描和并发用例使用数据库当前时间。保留操作不回写 Ticket 或 Seat。

## 全仓回归与版本

最终运行于 2026-11-11 08:35:57 完成，Maven 耗时 8 分钟。命令为 `mvn -o -B clean test -Dcache.redis.key-prefix=las:test:day22:20261111-082748-cbb5ac86:`，由隔离运行脚本调用，未限定测试类。1733 项中 1730 通过、失败 0、错误 0、跳过 3，实际退出码 0，BUILD SUCCESS。

最终版本包含 33 项改期 MySQL 集成、7 项时间政策和 2 项 Rabbit 拓扑用例，均随全仓通过。33 个改期集成场景的 13 脚本对账差异全部为 0；真实重复 Rabbit 窗口消息不重写响应。原 HTTP→RabbitMQ→售后 Trace 用例也已启用并通过。

三项跳过分别为：

1. `ProductCatalogMySqlIntegrationTest.realRedisStopPreservesTradesAndRecoveryDrainsDurableInvalidation`：此前明确暂缓的 Redis 实机停机。
2. `RabbitMqOutboxReliabilityMySqlIntegrationTest.realBrokerStopRetainsCommittedOutboxAndRecoveryDeliversIt`：此前明确暂缓的 RabbitMQ 实机停机。
3. `Day21ReconciliationMySqlIntegrationTest.allSqlScriptsUseOneReadOnlySnapshotAndSaveEveryResultSet`：面向保留演示库的独立入口，本次全仓关闭；Day 21 已单独验证通过。Day 22 在自身隔离数据上实际执行全部 13 个 SQL 脚本，未以空库替代对账。

基线 HEAD 为 `98aaa1d82a439fbd2efbb6505e2d3074a97150bc`，工作区包含此前未提交修改和本轮实现。各轮证据位于 `qa/verification/day22/<RunId>/`，保存运行前源码 SHA-256、原始 Maven 日志、Surefire XML、命令、退出码和资源清理日志。

最终证据目录为 `qa/verification/day22/20261111-082748-cbb5ac86/`。source-before.json 与 source-after.json 覆盖 src、tests 和 pom.xml，核对结果 `sourceUnchangedDuringRun=true`。本轮数据库和专属缓存键已清理，Rabbit 测试关闭消费者并删除自己的队列；初次连接地址错误留下的本次空库也已清理。未提交 Git，未对日常业务库执行 V17 迁移。

重跑入口为 `pwsh -NoProfile -File tests/concurrency/run-day22-tests.ps1`；全仓增加 `-FullSuite`。默认离线使用已有依赖，现有 JDK 23 与 IDEA 自带 Maven 路径写在脚本中。脚本读取本地 `.run/Day21-Product.run.xml` 的凭据，不打印密码。独立随机数据库与缓存前缀归本轮所有，测试完成后清理；Rabbit 专项自行清理独立命名的队列。

## 保留边界

Day 21 的 Redis、RabbitMQ 实机停机仍按用户此前要求暂缓；Day 22 没有将它们改记为通过。Tempo 导出与完整 Observability 仍按 Day 25 推进。

测试日志仍有旧测试上下文的模拟 DataSource 后台扫描记录，以及 Tempo 接收端不可达记录。它们与 Surefire 失败计数分开保留，没有通过隐藏日志或放宽交易断言计为通过。Day 23 取消批处理按计划继续，Day 22 没有未完成的必做业务项。
