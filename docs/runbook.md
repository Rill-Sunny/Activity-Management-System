# 发布版本运行手册

先按 [README](../README.md) 启动隔离环境。后续命令在该检出目录的 PowerShell 7 执行，`$run` 必须指向本轮 `LAB_DIRECTORY`，不能填开发数据库信息。

```powershell
. ./tests/system/common.ps1
$lab = Open-Lab $run
Lab-Compose $lab @('ps','--all')
Lab-Compose $lab @('logs','--no-color','--tail','100','migrate','app-a','app-b')
```

## 健康、指标与 Trace

应用业务端口的 `/actuator/health` 应返回 UP。Prometheus 地址取本轮 `.env` 的 PROMETHEUS_PORT，查询 `up` 应同时包含 app-a/app-b。应用 9091 管理端口只在容器网络内；采集使用独立 METRICS_PASSWORD，不能将采集凭据当作业务 JWT。

Grafana 使用本轮 GRAFANA_PORT，账号 admin，密码取私密 .env；数据源与 LAS 面板由 provisioning 导入。Tempo 使用 TEMPO_PORT；从演示 steps.json 取得 traceId，在 Grafana Explore 的 Tempo 数据源按 ID 查询。HTTP、Relay、Rabbit Consumer 通过 traceparent 传播；独立定时接管任务使用 Span Link，不承诺所有恢复都挂在同一父 Span。面板、指标和告警解释见 [可观测说明](observability.md)。

日志记录 requestId、traceId、spanId、实例及错误分类，不应记录 Authorization、完整 JWT 或密码。排查时按同一订单/事件与 traceId 关联，公开报告仅保存脱敏摘要。

## 异步任务排障与安全重试

| 现象 | 先检查 | 恢复操作 |
| --- | --- | --- |
| Outbox 长期未 SENT | available_at、attempts、last_error、租约；Broker 连接、confirm/return | 恢复 Broker，让 Relay 到期重领；不生成新 eventId 伪造补发 |
| 消费失败/DLQ | consumer_failure、consumer_process、原消息和业务订单 | 修复原因后用 ADMIN `POST /admin/outbox-events/{eventId}/replay`；成功消费事件拒绝重复重放 |
| 限定商品售后未完成 | ticket_qualification_after_sale_task 的状态、原因和次数；当前 Ticket 资格 | 所属发布者通过资格售后任务查询/重试接口恢复，见商品 API；保留原任务 |
| 取消批次未完成 | cancellation_batch 游标、item 状态、租约及错误 | 所属发布者查询 `/publisher/cancellations/{id}`，对 FAILED item 调用 `/items/{itemId}/retry`；COMPLETED 不重开 |
| 缓存陈旧 | cache_generation 与 cache_invalidation_task、Redis 恢复状态 | 保留持久失效任务由扫描补偿；不要手改 generation 绕过版本检查 |
| 数据库锁等待 | performance_schema.data_lock_waits、data_locks、innodb_trx 与对应 SQL | 找持有者、事务边界与固定锁顺序；禁止仅加大超时掩盖原因 |

所有重试应先查当前提交结果。连接断开不等于事务必然未提交，重用原订单/请求 ID，由持久幂等及条件状态更新决定结果。已取消场次即使账面库存恢复也不得重新售票。

## 数据库对账

先等待即时任务收敛。正常演示数据执行：

```powershell
Lab-Reconcile $lab (Join-Path $run 'manual-reconciliation')
```

脚本在同一个只读一致性快照中执行 `tests/sql` 全部 14 份 SQL，保存原始输出和逐脚本差异。`messaging_reconciliation.sql` 是业务事件诊断，其记录数不等于错误数；其余结果正常应为空。故意损坏库存的 p16 测试先断言准确检出，再恢复夹具，不能把注入阶段当作正常业务零差异。

## 备份、恢复、停止与重启

[备份恢复手册](operations.md) 提供 `backup-db.ps1` 与 `restore-db.ps1`。将本轮 context.json 的 project/envFile 传给脚本，恢复目标必须是新的 `las_restore_*` 库。恢复后逐表摘要、Flyway validate 和全部 SQL 都需通过；只看到 SQL 导入成功不等于业务可恢复。备份后的交易不在快照内，当前方案不是零 RPO。

```powershell
Lab-Compose $lab @('stop')
# 保持同一项目、配置和卷：
Lab-Compose $lab @('up','-d','--pull','never','--no-build')
```

禁止 `down -v`，不要用重新生成的随机密码连接已有卷。新环境初始化用户只对空卷生效。常规升级应暂停写入、备份、运行独立 migrate，再启动两个相同版本实例；不要编辑已应用的历史迁移或直接执行自动 repair。

## 故障注入边界

发布 JAR 仅包含 src/main；Day 28 强制退出探针在 tests/fixtures，Mockito 故障钩子在 src/test。真实停机脚本限定本轮项目与环境开关，不存在外部可访问的生产故障接口。模拟支付、模拟验收是一期公开业务能力，不属于故障注入后门。

完整真实 Redis/RabbitMQ 停启、ACK 前退出、租约接管、DLQ 重放及备份恢复证据见 [Day 28 报告](../qa/reports/day28-reliability-report.md)。不要在正式数据环境照抄测试的清理脚本。
