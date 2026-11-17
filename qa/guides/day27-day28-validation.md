# Day 27/28 隔离验收操作说明

这些脚本会创建和重置测试数据，并停止指定测试项目中的服务。只用于已经获准的本机隔离实验。不得把开发库的连接信息填入运行目录。

## 环境与数据边界

`tests/system/start-lab.ps1` 为 Day 27、Day 28 分别生成独立 Compose 项目、随机主机端口、密码和 JWT 密钥，保存在 `qa/verification/day27|day28/<运行编号>/`。`.env`、订单令牌、数据库备份和原始日志不提交 Git。两个环境不共享数据库、Redis、RabbitMQ 或数据卷。

镜像构建使用 `deploy/scripts/build-image.ps1`，默认 Maven 离线模式。运行器使用 `--pull never`。需要的镜像必须事先准备；脚本不会自动安装其他框架或依赖。本轮 k6 固定为 `grafana/k6:1.8.1`。修改代码后应先构建对应镜像，再把该镜像名和实际源码快照传给环境启动脚本。

```powershell
# 在仓库根目录执行。填写实际保存的源码提交与镜像名。
.\tests\system\start-lab.ps1 -Day 27 -Image las-backend:day27-baseline -SourceCommit <源码提交>
```

启动成功后输出 `LAB_DIRECTORY`。后续命令的 `-RunDirectory` 使用这个完整目录，不使用 `.env` 路径。

`tests/system/snapshot.ps1` 使用独立 Git 索引建立 `codex/day27-day28-*` 快照分支，并核对原暂存区摘要，不切换当前分支。工作区存在历史未提交内容时，以保存的源码快照和镜像 ID 识别被测版本，不能仅记录主分支 HEAD。

## Day 27 压测

```powershell
.\tests\load\run-phase.ps1 -RunDirectory <Day27运行目录> -Phase baseline
# 完成基线后修改并验证优化，构建新镜像，在同一环境切换到新镜像。
.\tests\load\run-phase.ps1 -RunDirectory <同一Day27运行目录> -Phase optimized
```

每个阶段运行 23 轮：S08 在 10/50/100 VU 下分别预热 120 秒、测量 300 秒；一次冷启动读取突发；S01～S07 和 S09 各一次 50 VU；S01/S03/S04/S05/S06 再各跑 10、100 VU；最后以 150 VU 争抢一个免费名额。

S08 每个虚拟用户并行读取 Activity 和 Product，因此 50 VU 最多同时发出 100 个 HTTP 请求。冷启动突发指第一次访问冷缓存后的整批读取，其中后续请求可以命中缓存；不能将整轮都称为缓存未命中。正式阶段不使用 `-Smoke`；它只将持续负载缩短为 5 秒预热和 10 秒采集，供脚本自检。

每轮先停应用、清空本项目消息队列、重置本项目业务表及 Redis，再启动两个应用。Flyway 历史保留。订单通过正式 HTTP 接口创建，JWT 对应真实 ACTIVE 测试用户，生产认证过滤器保持启用。有限库存场景只发送固定数量请求，避免循环请求已售罄库存。支付订单在测量前重新创建，以免 15 分钟有效期污染结果。

测量过程中不改代码或夹具。`k6-summary.json` 保留原始统计；`result.json` 分别记录 HTTP 请求数、预期业务拒绝、技术错误、有效业务提交数及 TPS。重复支付、退款的多个成功响应只计一次实际业务效果。持续负载的延迟和请求数不含预热。异步取消的 TPS 使用最终退款完成时间，不能用取消接口返回时间代替。

`samples.jsonl` 保存应用/JVM/Hikari、缓存、RabbitMQ 和 Outbox 指标，以及 MySQL 锁等待和取消游标。隔离环境 Prometheus 每 5 秒采集一次，常规部署仍使用原配置。`mysql-before.txt`、`mysql-after.txt` 保留锁等待计数、慢 SQL 和执行统计；每轮清空隔离服务器的语句统计，避免上一轮数据混入。短突发的采样峰值可能低于瞬时峰值，应结合累计等待时间及请求延迟解释。

每轮结束会检查业务数量，并执行 `tests/sql/` 下全部 14 个文件。`messaging_reconciliation.sql` 是事件诊断明细，其余脚本正常结果必须为空。失败轮次保留原始文件，修复后产生新的运行目录。

## Day 28 故障与恢复

先暂停性能环境，使用经过 Day 27 验证的镜像启动独立环境：

```powershell
.\tests\system\start-lab.ps1 -Day 28 -Image <已验证镜像> -SourceCommit <已验证源码提交>
.\tests\system\run-faults.ps1 -RunDirectory <Day28运行目录> -Scenario Redis
.\tests\system\run-faults.ps1 -RunDirectory <Day28运行目录> -Scenario Rabbit
.\tests\system\run-faults.ps1 -RunDirectory <Day28运行目录> -Scenario F01
.\tests\system\run-faults.ps1 -RunDirectory <Day28运行目录> -Scenario F02
.\tests\system\run-faults.ps1 -RunDirectory <Day28运行目录> -Scenario F06
.\tests\system\run-faults.ps1 -RunDirectory <Day28运行目录> -Scenario F07
.\tests\system\collect-fault-traces.ps1 -RunDirectory <Day28运行目录>
.\tests\system\backup-restore.ps1 -RunDirectory <Day28运行目录>
```

Redis 和 Rabbit 场景真实停止、启动本项目容器。恢复断言检查缓存 generation、持久失效任务、Outbox、消费幂等记录及实际业务结果，不只检查容器是否运行。

Day 28 默认全采样，用于查询短暂故障与 scheduler 接管的 Trace；Day 27 仍为 10% 采样。取消任务通过 Span Link 关联原事件，不把独立调度错误地拼成原 HTTP 的子请求。`collect-fault-traces.ps1` 实际查询 Tempo，核对发布/消费 Span 以及最后一次取消恢复的关联；未查询到的 Trace 不计为证据。

F01/F02/F07 使用独立 Java 进程调用生产服务，在明确的持久提交边界执行 `Runtime.halt(77)`：Broker Confirm 后而 SENT 更新前、业务提交后而 ACK 前、取消任务认领提交后。进程不执行关闭钩子。恢复后由正式双应用继续处理；测试不提前修改租约截止时间。探针仅存在于 `tests/fixtures/`，要求独立 Day 28 数据库和显式环境开关，不向生产应用增加故障接口。

F06 在本项目合法已付款订单的 Outbox 中故意损坏一个消息字段，验证三次尝试后的失败记录与 DLQ。修复该字段后通过 ADMIN 重放接口重用原 eventId，验证一次业务效果，并保存已解决的死信。F03 重复事件及旧到期事件由 Rabbit 场景覆盖；F04/F05 的跨事件售后幂等和重新购票后的迟到事件由 Java 集成测试覆盖。

## Java 全量与数据库证据

```powershell
.\tests\system\run-native.ps1 -RunDirectory <Day28运行目录> -SourceCommit <源码提交> -RealOutages
```

默认执行 Maven 离线 `clean test`。不提供 `-Selection` 才是全量；提供该参数时结果属于专项。默认 Java/Maven 路径适配本机，可用 `-JavaHome`、`-MavenCommand` 替换。运行器建立自己的随机临时库，结束后仅删除该临时库；不会删除 Day 28 业务库或数据卷。

`-RealOutages` 开启原来暂缓的两个真实停机用例，因此不要在另一场压测进行时运行。传统 Day 21 演示库对账入口仍由独立开关控制；本轮改用每场故障后的 14 份 SQL 和提交后 AT 快照。报告必须如实列出跳过项。

运行全仓前暂停本轮两个应用，保留 MySQL、Redis、RabbitMQ、Tempo 等基础设施；Maven 结束后恢复应用。这样停机测试及临时库夹具不会与业务演示的后台 Worker 争用资源。运行器在 `finally` 中清理自有临时库并恢复进程环境变量；原本不存在的变量必须删除，不能设为空串，否则会覆盖 Compose 环境文件中的有效值。

`tests/fixtures/day28/fault-matrix.json` 把事务故障、真实锁竞争、连接中断、资格事件及取消部分失败映射到具体方法。证据扩展在测试方法结束、夹具清理前保存独立一致性快照及全部 SQL 结果。测试运行期间源码摘要变化会使运行器报错。

连接中断新增测试使用真实 `KILL CONNECTION`。提交前终止应全部回滚；提交后场景先让真实 COMMIT 成功，再断开连接并注入“未收到提交确认”的异常。这验证应用面对未知结果的恢复语义，不声称模拟了网络设备丢包。后续查询、重试均使用原订单 ID。

## 备份及现场恢复

备份使用 `mysqldump --single-transaction`。恢复只创建新的 `las_restore_*` 数据库，不覆盖原库。验证包括逐表数据摘要、14 份 SQL、对应应用版本启动、业务查询及旧版本兼容验证。备份后额外完成一笔真实购买，确认它在原库存在、在恢复快照中不存在，从而明确本实验并非零数据丢失方案。

验收结束保留数据卷、备份、失败轮次和恢复后的业务记录。按开始时保存的容器清单恢复此前 Day 24～26 环境；不要执行 `docker compose down -v`。正式结论分别记录在 Day 27 压测报告和 Day 28 故障恢复报告中。
