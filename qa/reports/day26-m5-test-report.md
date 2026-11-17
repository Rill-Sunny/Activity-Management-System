# Day 26 M5 验收报告

执行日期：2026-11-13。**Day 26 已完成，M5 功能验收通过，按本报告范围冻结功能。** 最终全仓 1,780 项：1,777 PASS、0 FAIL、0 ERROR、3 SKIPPED；AT-01～AT-20、高风险 8 类和双实例 8 项均通过。真实 Redis/RabbitMQ 停机实验仍按约定留至 Day 28。

**2026-11-15 后续补验：** 两项真实停机已在独立 Day 28 环境执行通过；全仓 1,788 项，1,787 PASS、0 FAIL、0 ERROR、1 SKIPPED，123 份提交后快照及全部 SQL 正常差异为 0。唯一跳过为历史保留演示库对账入口。原本三个跳过的历史计数保持不变，最新证据见 [Day 28 报告](day28-reliability-report.md)；Day 27 的容量未达标项另行记录，不改变本报告的功能冻结范围。

## 环境与版本

| 项目 | 本轮实际值 |
| --- | --- |
| Spring Boot | 3.5.16 |
| Java | 原生 Maven：OpenJDK 23.0.1；应用容器：Temurin 23.0.2+7 |
| MySQL | 实际查询 VERSION()：8.4.11 |
| Redis / RabbitMQ | 实际运行版本：8.10.3 / 4.4.0（management 镜像） |
| Docker | 29.9.3；独立项目 `lasday26-20261113-121241-922096cb` |
| 应用实例 | app-a、app-b 两个独立 JVM，最终健康状态均为 UP |
| Flyway | 应用空库 V1～V18 全部成功；Java 空夹具库排除演示数据 V2，执行其余 17 个业务迁移 |
| 镜像 | `las-backend:day26`，ID：`sha256:2aecd2ba4e04dac94656cc8297674bffc8300656b524eb9ad7cec53a500490a5` |
| 最终被测源码 | `96c4216dfd0cd22f9b03921aa3a85d40a2e08081` |
| Git 保存方式 | 独立分支 `codex/day26-m5-snapshot`；当前工作分支 HEAD 仍为 `98aaa1d82a439fbd2efbb6505e2d3074a97150bc` |
| 工作区状态 | 包含此前开发的未提交内容及本次修改；未切换当前分支、未覆盖原暂存内容 |

环境实测见 [versions.json](../verification/day26/20261113-121241-922096cb/versions.json)。本轮全部使用已有缓存，Maven 离线执行，Compose 使用 `--pull never`，没有新增下载。

历史 V4、V7、V16 迁移测试保留原有目标版本。商品测试先保存 V7→V16 的观察值，再升级到当前 Schema 执行业务回归。没有使用 H2 或 mock 数据库替代真实事务、行锁和唯一约束。

最终核对 [466 个源码、测试和部署文件](../verification/day26/source-content-verification.json) 均与被测提交一致。镜像构建使用初始快照 `ce2f7bdae62c274ce74d68dc8ca0a8a4a4148da9`；随后仅修复测试和运行器，`src/main`、部署配置、`pom.xml` 及双实例脚本没有变化，因此本轮镜像和双实例结果适用于最终业务代码。

## 执行结果与修复过程

下表各轮独立计数，不能相加作为不同测试总数。专项包含全仓中的已有方法。

| 轮次 | Tests Run | PASS | FAIL | ERROR | SKIPPED | 退出码 | 证据 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 初始 AT 专项 | 87 | 64 | 9 | 14 | 0 | 1 | [specialist-121418](../verification/day26/20261113-121241-922096cb/specialist-121418/summary.json) |
| 首次修复专项 | 87 | 87 | 0 | 0 | 0 | 0 | [specialist-122106](../verification/day26/20261113-121241-922096cb/specialist-122106/summary.json) |
| 首轮全仓 | 1,780 | 1,703 | 0 | 74 | 3 | 1 | [fullsuite-122413](../verification/day26/20261113-121241-922096cb/fullsuite-122413/summary.json) |
| 最终扩展专项 | 162 | 162 | 0 | 0 | 0 | 0 | [specialist-123634](../verification/day26/20261113-121241-922096cb/specialist-123634/summary.json) |
| **最终全仓** | **1,780** | **1,777** | **0** | **0** | **3** | **0** | [fullsuite-123958](../verification/day26/20261113-121241-922096cb/fullsuite-123958/summary.json) |

最终全仓执行时间：2026-11-13 12:40:05～12:52:40（UTC+08），Maven 显示 **BUILD SUCCESS**。运行期间源码摘要未变化。原始 [Maven 日志](../verification/day26/20261113-121241-922096cb/fullsuite-123958/maven.log) 与 [验收汇总](../verification/day26/20261113-121241-922096cb/fullsuite-123958/acceptance-summary.json) 已保留。

本次修复 **3 个原有测试夹具问题，以及 1 个本轮引入的测试兼容回归**，生产业务代码保持原实现：

1. 商品行为测试停在 V16，当前取消模块对账缺表。保留历史迁移观察值后升级到当前版本。
2. 票务夹具删掉源订单/退款后遗留 Outbox，造成后续调用的孤儿差异。增加自有测试库内的消息衍生数据清理。
3. 第二票务分区故意置零后未恢复。新增精确异常断言，确认回滚及安全关闭后恢复夹具，再检查零差异。
4. 新增清理逻辑最初假设消息表存在，使历史 V4/V7 的 74 个调用在清理阶段报错。改为仅清理实际存在的表；这 74 项已在扩展专项及最终全仓中通过。

首次修复源码为 `978508100fbfefca933c93277ed1d7808e4b6746`，最后修复使用上表列出的最终源码。扩展专项的 162 项包括 87 个 AT 调用、74 个历史 Schema 调用、1 个商品历史迁移调用。没有增加锁等待超时掩盖问题。详细记录见 [缺陷清单](day26-defect-list.md)。

## 需求、高风险与数据库证据

[AT 矩阵](day26-at-matrix.md) 覆盖 20 个 AT、42 条映射、41 个不同方法。每个 AT 都有独立索引，可继续定位实际参数化调用、JUnit XML、数据库记录及源码提交。

`Day26EvidenceExtension` 在测试方法结束、`@AfterEach` 清理前，使用独立 MySQL 连接和只读一致性快照采集已提交状态。拒绝外围测试事务和关闭 Security 过滤器的 AT 配置；最终 87 份快照均有实际 Security 过滤器链。数据库记录不导出密码、JWT、消息正文等内容。

| 范围 | 本轮结论 |
| --- | --- |
| AT-01～AT-10 | FREE 占额、PAID 支付占资源、白名单、限购、同座/库存竞争、停售后的合法旧单付款及指定票退款均通过 |
| AT-11～AT-14 | SKU 不替换、多 SKU 回滚与关闭竞态、Product 共享限购、部分售后与条件回库均通过 |
| AT-15 | 真实 Outbox→RabbitMQ→Consumer 资格复核；保留普通商品、仍有有效票不联动、重新获票后迟到事件、发货后验收、失败查询及重放均通过 |
| AT-16～AT-18 | 配置冻结、专用改期 API、min(公告+7天, 新开始)、截止 -1ms/= /+1ms、已受理延迟执行、自动保留与用户确认区分均通过 |
| AT-19～AT-20 | 场次/活动取消隔离、独立退款项事务、有限重试、显式恢复及批次收敛均通过 |
| 高风险 8 类 | Security、座位、支付期限、退款金额、售后、资格、生命周期、消息可靠性全部通过 |
| 双实例 | 8 项通过；同请求、重复支付/退款、最后座位、共享 Product 限购、取消与支付、双 Relay、重复消费均通过 |
| AT 数据库快照 | 87 份 × 14 个脚本 = 1,218 次脚本执行，正常不变量差异 0 |
| 双实例业务库最终对账 | 14 份脚本全部完成；13 份不变量脚本差异均为 0，1 份消息诊断保留明细 |

金额与政策边界保留单元测试；事务、行锁、唯一约束和资源效果使用真实 MySQL。可控时钟和故障注入用于控制边界。部分数据库竞争测试隔离 Redis 去重提示，让两个事务实际进入数据库；双实例测试使用真实 Redis、RabbitMQ 和两个 JVM。

双实例证据：[8 项结果](../verification/day26/20261113-121241-922096cb/dual-instance/business-results.json)、[两个 Relay](../verification/day26/20261113-121241-922096cb/dual-instance/relay-instances.json)、[两个消费者](../verification/day26/20261113-121241-922096cb/dual-instance/rabbit-consumers.json)。本轮两个 Relay 分别发布 19、14 条事件；20 次重复投递最终只有 1 条消费标记和 1 条通知；10 次取消/支付竞争均满足数据库约束。

`messaging_reconciliation.sql` 输出诊断明细，其正常记录不是差异。完整结果见 [reconciliation.json](../verification/day26/20261113-121241-922096cb/reconciliation.json) 与 [原始 SQL 输出](../verification/day26/20261113-121241-922096cb/reconciliation.txt)。负向夹具另外保留：

- [商品 p16](../verification/day26/20261113-121241-922096cb/fullsuite-123958/verification/day16-product-out-of-stock-rollback.json)：准确检出 1 条 `SKU_AVAILABLE_STOCK_MISMATCH`，恢复后 0。
- [票务分区库存](../verification/day26/20261113-121241-922096cb/fullsuite-123958/verification/payment-stock-failure-independent-close.json)：准确检出 1 条 `ZONE_INVENTORY`，恢复后 0。

原生全仓日志检索到的 OTLP 导出失败为 0；本轮使用隔离 Tempo 的实际接收端口。消息连接失败、重投、ACK 前失败、有限重试及 DLQ 测试已运行，真实容器停机不计入这些结果。

## 跳过项与未完成范围

| 测试 | 状态与原因 | 后续 |
| --- | --- | --- |
| `ProductCatalogMySqlIntegrationTest#realRedisStopPreservesTradesAndRecoveryDrainsDurableInvalidation` | SKIPPED；本轮不停止 Redis | Day 28 真实故障注入 |
| `RabbitMqOutboxReliabilityMySqlIntegrationTest#realBrokerStopRetainsCommittedOutboxAndRecoveryDeliversIt` | SKIPPED；本轮不停止 RabbitMQ | Day 28 真实故障注入 |
| `Day21ReconciliationMySqlIntegrationTest#allSqlScriptsUseOneReadOnlySnapshotAndSaveEveryResultSet` | SKIPPED；保留演示库的独立入口未启用 | 本日用隔离夹具执行全部 SQL，原入口仍如实记为未执行 |

当前验收范围内无未解决 P0/P1。上述跳过项不记为 PASS，也不作为真实停机恢复已经验证的证据。Day 27 性能结论及 Day 28 完整故障矩阵不属于本日通过结论。

## 复现步骤

从仓库根目录的 PowerShell 7 执行，使用现有缓存与镜像：

```powershell
.\deploy\scripts\build-image.ps1 `
  -MavenCommand 'D:\Computer_Science\IntelliJ IDEA 2026.2.1\plugins\maven-plugin\lib\maven3\bin\mvn.cmd' `
  -JavaHome 'C:\Users\32170\.jdks\openjdk-23.0.1' `
  -Image 'las-backend:day26'
.\tests\acceptance\run-day26-tests.ps1 -Stage Start
```

用 Start 输出的新目录替换下面的占位值；双实例夹具只创建一次，完整重跑使用新环境。

```powershell
$run = 'qa/verification/day26/<新运行编号>'
$commit = '96c4216dfd0cd22f9b03921aa3a85d40a2e08081'
.\tests\acceptance\run-day26-tests.ps1 -Stage Specialist -RunDirectory $run -SourceCommit $commit
.\tests\acceptance\run-day26-tests.ps1 -Stage DualInstance -RunDirectory $run
.\tests\acceptance\run-day26-tests.ps1 -Stage FullSuite -RunDirectory $run -SourceCommit $commit
.\tests\acceptance\run-day26-tests.ps1 -Stage Reconcile -RunDirectory $run
.\tests\acceptance\collect-day26-results.ps1 -PhaseDirectory "$run/<fullsuite-时间>" -RunDirectory $run
```

本轮全仓的实际 Maven 命令为：

```text
mvn -o -B clean test -Djunit.jupiter.extensions.autodetection.enabled=true -Dcache.redis.key-prefix=las:test:day26:day26_suite_dfc2a690827c4fbca9a5c3647412b187:
```

运行器自动连接本次 Compose 的随机回环端口并设置环境，不需要在命令中写密码。最终 [环境核对](../verification/day26/20261113-121241-922096cb/final-environment-check.json) 确认临时测试库剩余 0；两个应用与隔离数据卷保留，开发数据库未操作，Day 24/25 容器未暂停。

## M5 功能冻结

冻结基线为 `96c4216dfd0cd22f9b03921aa3a85d40a2e08081`，范围为一期需求、公开 API 契约、核心数据库业务规则、状态机及幂等语义。[冻结清单](../verification/day26/freeze-manifest.json) 保存 329 个相关文件的 Git 内容摘要。

后续以压测、必要性能优化、故障恢复及发布交付为主。正确性 Bug 允许修复，但须更新验收版本、记录原因、复跑受影响验收和全仓测试。

原始日志、JUnit XML、数据库快照和每轮失败证据保存在本地 `qa/verification/day26/`。Git 交付保存报告和证据摘要，私密环境文件按现有忽略规则保留在本地；[证据摘要索引](day26-evidence-index.json) 用 SHA-256 固定本轮 183 份证据文件。报告使用上述被测源码提交作为测试结论的依据；文档交付另存提交。
