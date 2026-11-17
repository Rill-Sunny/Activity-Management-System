# Day 29～30 最终发布与 M6 验收

执行日期：2026-11-15。**M6 按一期业务正确性与工程交付标准通过。** Day 29 修复商品 HTTP 履约闭环缺口，Day 30 从独立源码检出和全新数据库完成启动、全仓测试、双实例竞争、完整演示、观测、持久化与备份恢复。性能参考目标未全部达成，作为已知 P2 容量限制保留。

## 版本与实际运行范围

- RC：`v1.0.0-rc1`，源码 `6aa8e757519ff1a6313c1e5c9007b107c7b45603`。正式本地标签 `v1.0.0` 的收尾提交只补充文档与结果，`src/tests/deploy/pom.xml` 与已测 RC 相同；最终提交用 `git rev-parse 'v1.0.0^{commit}'` 查询。
- 独立检出：`C:\Users\32170\AppData\Local\Temp\las-release-check-day30-20261115-202234`。使用本地 clone 获取已冻结 Tag，运行前后 `git status --porcelain` 均为空。复用已安装 Java/Maven、依赖缓存和固定镜像；业务数据库和卷全部新建，不把开发库当作空库。
- 环境：Java 23.0.1（原生测试），Temurin 23.0.2+7（容器），Boot 3.5.16，MyBatis 3.0.5，MySQL 8.4.11，Redis 8.10.3，RabbitMQ 4.4.0；双应用，Docker 可用内存约 16 GB，主机 22 逻辑核。镜像 `las-backend:1.0.0-rc1`，ID `sha256:037010d18f882064f2130cb5dbc7a2d645e026ee98d579affce9cf283252786c`。
- 生产变化仅为 `ProductOrderResponse.Item.orderItemId`、对应响应映射及 Maven 发布版本 1.0.0。未更改交易算法、锁顺序、索引、资格、缓存或 Consumer。V1～V18 与 Day 28 逐文件一致。
- 原始完整证据已复制回工作区 `qa/verification/day30/20261115-202331-cada828e/`。私密配置、日志和备份不提交 Git；[机器结果](day30-acceptance-results.json)、[AT 矩阵](day30-at-matrix.md) 和 [SHA-256 索引](day29-day30-evidence-index.json) 提供可核查关联。

原 main 保持 `98aaa1d82a439fbd2efbb6505e2d3074a97150bc`，原暂存区 SHA-256 保持 `3F6077ECF092AB345420ACF90EA155C5FE66B485FC7C54E1FD0F486A59D72D60`，包括用户暂存的 IDE 文件。发布文件保存在独立 `codex/release-*` 分支；没有远程推送或公开发布。不能用原 main HEAD 代表本轮发布版本。

## 测试与正常数据库差异

| 验收 | 实际结果 | 源码/证据 |
| --- | --- | --- |
| 原始缺陷回归 | 1 FAIL → 修复后 PASS | `95f3754` 原失败；`1e341778` 修复；见缺陷清单 |
| 商品完整回归 | 132/132，0 FAIL/ERROR/SKIPPED | `8668ed9a`，真实 Redis 停机开启 |
| 干净目录全仓 | **1790 项：1789 PASS、0 FAIL、0 ERROR、1 SKIPPED；exit 0** | RC；`native-20261115-202456` |
| AT-01～AT-20 | 20/20 PASS | 逐方法 JUnit、夹具与提交后证据已关联 |
| 八类高风险 | 8/8 PASS | 权限、选座、时间、退款金额、售后、资格、生命周期、MQ |
| 提交后快照 | 124 份，覆盖 AT 与十类故障 | 每份全部 14 SQL；正常差异 0 |
| 双实例 | 8/8 PASS | 重复下单/支付/退款、最后座位、商品共享限购、取消 vs 支付、双 Relay、重复消费 |
| HTTP 完整演示 | 81 检查，全部通过；153.65 秒连续执行 | 75 HTTP、6 只读 SQL；另有一次时间参数准备 |
| 新环境最终对账 | 14 SQL，正常不变量差异 0 | 双实例、演示、恢复库、重启后分别执行 |
| 备份和持久化 | 43 张表摘要一致；Redis 标记/Rabbit 持久消息保留 | 恢复到新库，重建容器保留卷，重复 Flyway validate |

全仓在 20:25:15～20:37:04 执行，502 个源码/测试/部署文件在运行前后摘要不变。命令为：

```powershell
pwsh -NoProfile -File tests/system/run-native.ps1 -RunDirectory <独立 Day30 运行目录> -SourceCommit 6aa8e757519ff1a6313c1e5c9007b107c7b45603 -RealOutages
# 内部：mvn -o -B clean test -Djunit.jupiter.extensions.autodetection.enabled=true
# 以及本轮独有的 Redis key-prefix；全部实际参数保存在 summary.json。
```

唯一跳过为 `Day21ReconciliationMySqlIntegrationTest.allSqlScriptsUseOneReadOnlySnapshotAndSaveEveryResultSet`，其开关故意关闭，避免访问旧保留演示库。该用例仍计为 SKIPPED；全部 14 份 SQL 已在本轮提交后快照和真实业务库中执行，不存在以该跳过掩盖对账缺口。Redis/RabbitMQ 两项真实停机已执行。p16 故意制造的库存异常在测试中检出 1 条，恢复夹具后正常对账为 0。

## 环境、观测与恢复

空库执行 V1～V18，42 张应用表加 Flyway 历史共 43 张表，无物理外键。原镜像重复迁移验证通过，未修改 checksum 或执行 repair。两个应用使用非 root、只读根文件系统；所有宿主机端口绑定回环地址，9091 未映射到宿主机。

Prometheus 实际采集两实例，Grafana provisioning 导入 21 面板及 Prometheus/Tempo 数据源。按演示的最后一张票退款 traceId 查询 Tempo，包含真实 HTTP、`outbox.publish`、`mq.consume`、`product.aftersale`；重建后同一 Trace 仍可查询。限定商品退款独立成功，普通商品明细未误退。

备份恢复至 `las_restore_2724bd5f6e49490c996d58139590cb0f`，43 表逐表摘要一致。应用暂停写入后重建本轮容器，MySQL 数据、Redis AOF 标记和 RabbitMQ durable queue 中的持久消息仍在。恢复与重启后分别全部对账，差异 0。此处不宣称零 RPO；Day 28 已通过备份后新交易不在恢复库的实验明确边界。

本轮临时 Java 测试库已清理。Day 29/30 的容器和网络最终移除，**12 个命名数据卷全部保留**，备份和私密配置亦保留。重新打开本轮 Day 30 演示：

```powershell
# 在原工作区根目录执行
. ./tests/system/common.ps1
$lab = Open-Lab 'qa/verification/day30/20261115-202331-cada828e'
Lab-Compose $lab @('up','-d','--pull','never','--no-build')
$lab.appA
$lab.appB
```

此前 24 个 Day 24～26 隔离服务均已恢复。恢复时发现 Windows 将 11080～11179 标为保留端口，原 Day 24 四个容器无法绑定；释放本轮网络仍无效。最小修复仅将原 Day 24 的 app-a/app-b/Prometheus/Grafana 回环端口 **11176/11177/11178/11179 → 21276/21277/21278/21279**。原镜像、环境变量、卷挂载均逐项核对保留，私密原配置已备份，原 `.env` 端口同步更新，未更改 Windows 网络设置。其余旧服务端口不变。

## 性能与可靠性的版本边界

[Day 27 报告](day27-load-report.md) 保留 23 轮正式数据：22 轮通过，热点票务 S04/100 VU 失败，200 请求中 38 技术错误（19%），p95 5600.83 ms。原因是热点行串行竞争占用 Hikari 连接，触发 3 秒获取超时；没有靠加超时掩盖。该报告与旧源码/镜像明确关联，不写成新版本重新执行了所有场景。

此次追加修复版本商品 S06 10/50/100 VU：20/100/200 请求，10/50/100 真实成交，其余均为正常限购拒绝，技术错误 0；p95 **939.02/2836.98/4563.48 ms**，有效 TPS **10.58/17.58/21.01**，全部对账零差异。正确性通过，但 50/100 VU 的 1 秒参考目标未达。见 [原始汇总](day29-product-load-results.json)。

Day 27 持续热读取各档 120 秒预热、300 秒采集，50 VU p95 95.01 ms、约 1970 RPS；取消同条件 100 笔退款收敛 54.03→36.64 秒、TPS 1.85→2.73。不能用读取吞吐替代支付 TPS。

[Day 28 故障报告](day28-reliability-report.md) 的真实 Redis/RabbitMQ 停启、Confirm 后/ACK 前 JVM 强制退出、真实租约接管、DLQ 重放、Trace 和备份恢复均有证据。本轮全仓复测了真实停机、事务中断、真实死锁/锁等待及相关消费用例；没有把未重跑的独立 JVM 崩溃实验写成 Day 30 新结果。对应异步实现未变，历史版本和证据保留。

## 交付检查与遗留项

交付入口：[README](../../README.md)、[需求](../../docs/requirements.md)、[架构](../../docs/architecture.md)、[Schema](../../docs/schema.md)、[决策](../../docs/decisions.md)、[锁](../../docs/locking.md)、[状态机](../../docs/state-machines.md)、[OpenAPI](../../docs/api/openapi.yaml)、[Apifox 集合](../../tests/acceptance/apifox-collection.json)、[运行手册](../../docs/runbook.md)、[完整/八分钟演示](../../docs/demo.md)、[简历事实](../../docs/release-facts.md)、[进度](../../docs/schedule.md)。

OpenAPI 69 操作由生产控制器/DTO 生成并通过一致性测试；75 请求集合使用 Postman 2.1 格式，已实际执行对应 HTTP 流程。未冒称操作过 Apifox 桌面 UI。自动演练不等于录制了八分钟视频；交付的是可运行完整脚本及八分钟讲解提纲，符合 API 步骤交付范围。

没有未解决的 P0/P1 业务正确性或权限缺陷，没有本轮必交业务/故障证据缺口。保留 P2 热点支付容量和部分交易延迟限制；历史 Day 21 独立夹具入口仍跳过。生产 JAR 不含测试类或强制退出探针；凭据只在本地忽略文件中生成。没有新增框架、下载依赖或推送远程。

## Day 29～30 完成反馈

```text
【Day 29～30 最终完成情况】

一、Day 29 发布候选
Day26～28未完成事项：真实停机与故障缺口已在 Day28 补齐；性能参考目标部分未达，保留 P2。
发现的P0/P1缺陷：无新增 P0；1 项 P1：商品响应缺少履约/售后所需明细 ID。
实际修复项：补 orderItemId；脱离旧类路径缓存；完善契约、演示、证据目录及当前文档。
失败用例复测：原始 1 FAIL → PASS；商品模块 132/132。
全量测试结果：1790 总计，1789 PASS，0 FAIL/ERROR，1 SKIPPED，exit 0。
正常对账差异：0。
故障注入开关检查：测试探针/钩子未进入生产 JAR，无公开强制失败入口。
配置与密钥检查：占位配置、随机本地凭据、私密文件排除；原迁移摘要未改。
RC版本/Commit：v1.0.0-rc1 / 6aa8e757519ff1a6313c1e5c9007b107c7b45603。
未解决问题：热点支付容量、部分 p95 未达参考目标。

二、Day 30 环境验收
干净目录部署：独立临时 clone，Git 干净，复用固定缓存工具。
Flyway空库迁移：V1～V18；重复校验通过。
双实例运行：两实例健康，八项跨实例实验通过。
MySQL/Redis/RabbitMQ：真实业务与持久化重启通过。
Prometheus/Grafana/Tempo：双 target、21 面板、真实跨域 Trace 及重启后查询通过。
持久化与重启：43 表摘要一致，Redis 标记与 Rabbit 持久消息保留。
全量测试结果：1790/1789/0/0/1（总数/通过/失败/错误/跳过）。

三、业务验收
AT-01～AT-20：20/20 PASS。
权限与并发：八类高风险及八项双实例 PASS。
票务/商品/退款：完整 HTTP 和原生回归 PASS。
改期/取消：特殊退款、真实到期保留、批次收敛 PASS。
消息幂等：重复发布/消费与跨事件售后不重复效果。
最终数据库对账：124 份提交后快照及真实业务库全部正常差异 0。

四、性能与可靠性
50 VU p95：本轮商品 S06 2836.98 ms；Day27 热读取 95.01 ms，场景分别报告。
有效成交TPS：本轮 S06/50 VU 为 17.58；读取 RPS 不算成交 TPS。
非预期技术错误率：本轮 S06 三档 0；Day27 热点票务100 VU仍为19%。
主要性能瓶颈：热点资源行串行竞争与连接池排队。
优化前后对比：Day27 取消100笔54.03→36.64秒，1.85→2.73 TPS；未宣称HTTP延迟改善。
Redis真实停机恢复：Day28 6.80秒；本轮全仓停机用例再次通过。
RabbitMQ真实停机恢复：Day28 16.22秒；本轮全仓停机用例再次通过。
数据库故障恢复：真实死锁/锁超时、KILL连接、提交结果未知回归通过。
备份恢复：Day28 时点边界明确；本轮43表摘要及全部SQL一致。
未完成实验：本轮必交无；历史夹具入口1项跳过，未计通过。

五、最终交付
README：已更新并按流程从独立检出验证。
OpenAPI/Apifox：69操作/75请求；契约和真实HTTP流程验证。
架构/Schema/锁文档：已核对最终实现，说明零物理外键成本。
测试报告：本报告、Day30 AT矩阵及机器结果。
压测报告：Day27原始报告及Day29商品复测。
故障报告：Day28真实实验及本轮全仓、恢复证据。
运行手册：docs/runbook.md，含重试、备份、锁等待与恢复边界。
演示脚本：完整81检查，153.65秒；另有八分钟讲解提纲。
正式Release Tag：v1.0.0（仅本地）。
最终Git commit：用 git rev-parse 'v1.0.0^{commit}' 核对；最终仅文档/报告收尾。

六、最终总结
Day29有效开发时间：自动工作约19:25～20:22，含约16分钟会话中断；不等同人工净工时。
Day30有效开发时间：20:22起完成干净检出、测试、环境恢复与交付；净人工工时未统计。
项目是否达到M6：一期业务与工程交付通过；不宣称全部性能目标达标。
最终遗留问题：P2容量/延迟、1项历史测试入口跳过；单机Compose与备份不提供高可用/零RPO。
最有价值的三个工程设计：数据库原子资源事务；Outbox与消费幂等；generation与持久失效补偿。
最值得复盘的一次Bug：测试通过JDBC找明细ID，掩盖真实HTTP客户端无法完成售后。
AI在项目中的主要作用：辅助实现、定位失败、自动回归与证据核对；结论取自真实运行。
```
