# 执行 Day 25 可观测性验收

先确认 Docker 已启动，Java 23、Maven 和项目依赖已存在。脚本默认使用已缓存镜像和离线依赖，不会主动下载。新机器需要先取得下载许可，再按 [部署手册](../../docs/deployment.md) 准备环境。

## 构建并运行

在仓库根目录的 PowerShell 中执行；把本机 Maven 和 Java 路径换成实际路径：

```powershell
.\deploy\scripts\build-image.ps1 `
  -MavenCommand 'D:\Computer_Science\IntelliJ IDEA 2026.2.1\plugins\maven-plugin\lib\maven3\bin\mvn.cmd' `
  -JavaHome 'C:\Users\32170\.jdks\openjdk-23.0.1' `
  -Image 'las-backend:day25'
.\tests\observability\run-day25-tests.ps1
```

第二条命令会生成独立项目、数据库、随机端口和随机凭据。输出目录为 `qa/verification/day25/<运行编号>/`。不要分享其中的 `.env` 或 `state.private.json`，它们包含本轮凭据，已由目录规则排除 Git。

也可以分阶段执行：

```powershell
.\tests\observability\run-day25-tests.ps1 -Stage Start
$run = 'qa/verification/day25/<刚生成的运行编号>'
.\tests\observability\run-day25-tests.ps1 -Stage Business -RunDirectory $run
.\tests\observability\run-day25-tests.ps1 -Stage Observe -RunDirectory $run
.\tests\observability\run-day25-tests.ps1 -Stage Faults -RunDirectory $run
.\tests\observability\run-day25-tests.ps1 -Stage Reconcile -RunDirectory $run
```

`Business` 创建一次性数据，修复代码后完整重跑应创建新的运行目录，不能在同一数据库重复执行它。故障阶段停止并恢复 app-a，Redis/RabbitMQ 容器始终运行。异常路径也会尝试恢复应用配置；若脚本被强行终止，使用该目录 `context.json` 中的项目名和 `.env`，以基础 Compose 配置重新启动 app-a。不要带 `redis-fault.yaml`。

全量 Java 回归使用现有隔离脚本：

```powershell
.\tests\concurrency\run-day23-tests.ps1 -FullSuite
```

该脚本沿用已有本机连接配置，创建并清理自己拥有的临时数据库和 Redis 进程，不重用 Docker 验收库。记录其实际输出目录、总数、失败/错误/跳过和源码校验结果。命令内部默认执行 Maven 离线 `clean test`。

## 查询观测结果

1. 打开运行目录的 `endpoints.json`，取得本轮应用、Prometheus、Grafana 和 Tempo 地址。
2. 在 Prometheus 的 **Status → Target health** 检查 `las-backend` 下 app-a、app-b 两个 UP，以及 `las-rabbitmq` 的 UP。
3. 登录 Grafana，用户名 `admin`，密码取本轮 `.env` 的 `GRAFANA_PASSWORD`。进入 **Dashboards → LAS → LAS Backend Overview**，时间范围选择覆盖本轮验收。
4. 打开 `trace-linkage-summary.json`，复制 `traceId`。在 Grafana **Explore** 选择 **Tempo**，使用 **Trace ID** 查询。检查 `ticket.refund → outbox.publish → mq.consume → product.aftersale` 及父 Span 关系。
5. 在日志中按同一 traceId 过滤；在数据库按该文件的 eventId 查询 `outbox_event`、`consumer_process` 和 `ticket_qualification_after_sale_task`。不要用资金账本口径解释幂等调用计数。
6. 打开 Prometheus **Alerts**，检查三条规则；Grafana 的 **Firing alerts** 图表展示当前正在触发的状态，历史曲线用于查看恢复前的状态。

Grafana 配置和 JSON 通过 Compose 自动挂载，无需手工保存面板。修改面板应编辑仓库内 JSON 后重载/重启 Grafana，保持代码与部署一致。

## 故障证据与恢复

| 场景 | 如何制造 | 成功证据 | 恢复 |
| --- | --- | --- | --- |
| 库存不足 | 两个待支付订单竞争最后库存 | 409、库存拒绝计数、无技术错误标记的业务 Span | 保留真实失败订单用于对账 |
| Outbox 等待 | 仅把测试事件的下次尝试延后，创建时间保持真实 | Pending、自然增长的 oldest age 和 Firing 时间 | 恢复当前可发布时间，Relay 自动发布 |
| 单实例不可用 | 停止 app-a 至少一分钟 | A DOWN、B UP、实例告警 | 启动 app-a，等待 UP 和 resolved |
| 毒消息 | 持久化一个通知正文为空的测试事件 | 三次失败、两次已确认重试、实际 DLQ、无成功 marker/effect | 修正该夹具载荷，管理员重放，确认业务仅一次，再 ACK 原 DLQ 副本 |
| Redis 连接失败 | 仅给 app-a 配置错误端口 | 回源、readiness 503、延迟、重复支付/退款不改库存限购 | 以基础配置重建 app-a，检查命中恢复 |

`fault-timeline.json` 记录注入、Firing、定位、恢复和 resolved 时间。`reconciliation.json` 记录全部 SQL 结果，其中 `messaging_reconciliation.sql` 是诊断明细，不把其正常行当作差异。日志脱敏检查对照本轮真实凭据，但只输出检测数量。

本轮不执行 Redis/RabbitMQ 容器在交易过程中的真实停机恢复矩阵，该任务保留 Day 28。
