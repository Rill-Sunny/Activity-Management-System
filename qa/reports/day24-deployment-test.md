# Day 24 Docker 部署验收

执行日期：2026-11-12。Day 24 后端必做项完成。最终 Docker 全流程验收及冻结源码后的 Maven 全仓复跑均通过。Day 22 的改期 33 项、Day 23 的取消 37 项在本日全仓中再次通过。

## 已实现

- 固定 Java 23 镜像摘要的 Dockerfile、非 root 用户、只读应用容器、优雅停止。
- core 与 observability profiles；双应用、独立迁移、共享中间件、六个持久化卷。
- 环境变量校验、随机配置生成、独立指标账号和 Grafana 数据源配置。
- 宿主机字节流备份、拒绝覆盖的独立数据库恢复、逐表主键排序摘要比较。
- 真正通过两个容器 HTTP 地址发起的并发验收，及全部 14 份 SQL 对账。

## 已执行与修复

七个指定运行镜像下载成功，实际摘要保存在 `deploy/images.lock.json`，Dockerfile、Compose 与示例环境保持一致。JAR 和镜像首次构建成功，容器配置单元测试 3 项通过。

空库实际完成 Flyway V1～V18；两个实例使用 Java 23.0.2、Spring Boot 3.5.16 启动，健康状态为 UP，migrate 退出 0。

首轮发现验收端口 25672 与本机 RabbitMQ 节点端口冲突，已改为动态分配可绑定回环端口。没有停止本机 RabbitMQ。

并发支付首轮返回一次 200 和一次 409 REQUEST_IN_PROGRESS，库存只扣一次。该结果符合既有共享 Redis 去重契约。修正脚本对两个响应必须均为 200 的过严断言，随后必须从两个实例重试并验证一次业务效果。另修正从受控 409 响应读取 Ticket 的脚本错误。

V2 历史迁移写死数据库名且未提交内部事务，导致新库部署不可靠。本日移除 USE，明确提交并恢复时区。原数据库未执行迁移或 repair；已执行旧 V2 的升级风险在部署手册单列。

## 最终结果

最终部署 RunId：`20261112-114840-f9059700`。`run-day24-tests.ps1` 的 All 阶段于 11:53:29（+08:00）完成，passed=true，进程退出 0。core 单独启动验证通过，再加入 observability；完整停止重启后，两个应用再次就绪。

8 组跨实例业务场景全部通过，包含 10 轮取消/支付竞争。两个 Relay 分别确认 23 和 10 个事件，总计 33 个业务事件发送完成。RabbitMQ 同一支付队列的两个消费者来自不同应用地址；20 次重复投递只留一份业务通知和处理记录。

业务完成后、恢复库、重启后三次执行全部 14 份 SQL，正常不变量差异均为 0。备份恢复及重启前后的 43 张表（42 张应用表加 Flyway 历史）逐表行摘要一致。恢复后的 CHECK、唯一约束及索引定义比较一致。

Redis AOF 标记保留；RabbitMQ 固定节点名下的持久队列及一条持久消息保留。Prometheus 的两个 target 均 UP；Grafana 数据库健康且两个数据源实际加载；HTTP trace 在 Tempo 可查询。指标端点实际返回：未认证 401、内部认证 200、应用业务端口携带指标凭据仍为 403。

最终全仓 RunId 为 `20261112-114828-a0998cd6`，1773 项、1770 成功、0 失败、0 错误、3 跳过，BUILD SUCCESS、进程退出 0，Maven 耗时 8 分 14 秒。运行时间为 11:48:34～11:56:51（+08:00），sourceUnchangedDuringRun=true。证据完整复制到 `qa/verification/day24/final-fullsuite/`，包含原始日志、Surefire XML、Day 22/23 场景对账、前后摘要及绑定快照提交的 evidence-binding.json。

三项跳过仍为 Redis 实机停机、RabbitMQ 实机停机及 Day 21 保留演示库独立对账入口。本日 14 份 SQL 在实际隔离数据上已执行，不因该入口跳过而省略对账。

第一轮全仓同为 1773 项、1770 成功、0 失败、0 错误、3 跳过，退出 0，耗时 7 分 46 秒。期间部署脚本发生修改，runner 如实记录 sourceUnchangedDuringRun=false；最终结论使用后续冻结复跑。

复现命令：

```powershell
pwsh -NoProfile -File deploy/scripts/build-image.ps1 -Online
pwsh -NoProfile -File tests/deployment/run-day24-tests.ps1
pwsh -NoProfile -File tests/concurrency/run-day23-tests.ps1 -FullSuite
```

本日源码校验单测 `ContainerEntrypointTest` 为 3/3，通过缺失配置、占位密钥、短 JWT 与合法配置测试，包含在全仓统计中。测试依赖使用现有缓存，运行依赖新增 `micrometer-registry-prometheus`。

本轮全量实际命令为 `mvn -o -B clean test -Dcache.redis.key-prefix=las:test:day23:20261112-114828-a0998cd6:`，复用 Day 23 的独立库 runner。本机服务上的全仓回归和 Docker 中的双实例验收分别保存证据。

原始全仓日志没有 `Lock wait timeout exceeded`，保留 9 条既有本机测试 Trace 导出失败记录，未将其隐藏或计作 JUnit 失败。Docker 环境的 OTLP 地址为内部 Tempo，已通过真实 trace 查询。迁移日志另有当前 Flyway 适配器对 MySQL 8.4 的支持范围提示，本次 18 份迁移及重启 validate 均实际成功；没有自动升级 Flyway 或修复旧库历史。全仓 runner 的隔离资源清理日志为空，退出 0。

失败记录没有删除：`113123` 轮为本机端口冲突；`113210`、`113736` 轮为验收脚本的并发返回码及响应读取问题；`113925` 轮业务、备份恢复通过，但 Tempo 首次就绪探测遇到内部稳定等待，Observe 独立复跑通过。最终轮加入有界就绪轮询后整链一次通过。

## 验收矩阵

| 场景 | 已观察到的业务结果 |
|---|---|
| 同 requestId 跨实例创建 | 200/201 返回同一 orderId，数据库仅一单 |
| 同一订单同时支付 | 一次成功，另一次允许精确的 REQUEST_IN_PROGRESS；完成后双端重放成功，Ticket、库存和限购仅变化一次 |
| 最后一张票 | 一次 200、一次受控 409，无超卖 |
| 重复退款 | 同一退款请求与两实例处理，仅一个成功退款明细，库存和限购各恢复一次 |
| Product 共享限购 | SKU A/B 竞争剩余一份额度，仅一个成功，总购买量 2，另一个精确返回 PRODUCT_PURCHASE_LIMIT_EXCEEDED |
| 两个 Outbox Relay | 两实例日志均有成功确认，业务事件最终 SENT；无永久遗留 CLAIMED |
| 两个 MQ Consumer | 同一队列两个消费者连接，20 次重复投递只产生一条 notification 和一条 consumer_process |
| 取消与支付 | 10 轮跨实例竞争，允许支付先提交再全额退款或取消先提交拒绝支付；最终仅 REFUNDED/CLOSED，账面资源恢复 |

数据库检查同时包含票务、商品、退款、资格联动、改期、取消和逻辑关联。所有 14 个 SQL 文件在同一只读 RR 快照执行；13 个不变量文件要求零行，`messaging_reconciliation.sql` 作为诊断记录保存。

## 证据与源码约束

受测源码快照提交：`b80a004c2f4aeda53a33f1b733f0c9f370875889`，位于 `codex/day24-deployment-snapshot`。通过独立临时 index 创建，保留用户 main 分支及原 `.idea/db-forest-config.xml` 暂存内容。

快照 ZIP 位于 `qa/verification/day24/final-source/tested-source.zip`，SHA-256 为 `47AD5AA6989A1591BEA44B11996214B759BF083323E35E6ACDCB0E753272F67E`。441 个源码、测试与部署文件的 Git 规范化内容逐个匹配该提交。应用镜像 ID 为 `sha256:276c966bc8f3ea4e3f10f547a5d32b62615df8e95f64dd190010b4cfae86b4d2`；镜像 JAR SHA-256 为 `62BFD14095F1ABF782C699A5943856F688E87D1955B214D4AD046FB2CE0E97D2`。其中 376 个应用类及资源与本机 Maven 产物逐个匹配。

全量 runner 的 gitHead 字段记录当前工作分支基线 `98aaa1d82a439fbd2efbb6505e2d3074a97150bc`，不能单独代表本日代码；实际源码由上述快照提交、运行前后源码清单和镜像文件比较共同确认。

## 保留边界

- 新库已验证；原开发库未迁移或 repair。已有旧 V2 的库必须先处理历史校验差异。
- 模拟支付退款沿用现有业务范围，没有接入真实支付网关。
- 完整 Dashboard、告警及深入链路排障留到 Day 25。
- 本轮 down/up 验证持久化；Day 21 留存的交易中途 Redis/MQ 实机停机矩阵仍按约定留到 Day 28，不据此宣布 M3 已补齐全部证据。
- 失败轮及最终轮的独立数据卷均保留，普通停止没有使用 `down -v`。测试环境中的随机凭据和备份放在 Git 忽略的证据目录。
- 最终隔离环境保持运行：应用 A 为 `http://127.0.0.1:11176`，应用 B 为 `http://127.0.0.1:11177`；常规部署默认端口仍为 8081/8082。其他访问参数与停止方式从该轮 context.json 及本地 `.env` 读取。
- 附件的算法与计算机基础复习材料见 [学习补充](../../docs/day24-learning-review.md)；未代替学习者声明已完成练习。

## 2026-11-15 恢复环境补记

Day29/30 验收结束恢复旧服务时，Windows 保留范围 11080～11179 阻止原四个端口绑定。仅将本报告 Day24 环境的 app-a/app-b/Prometheus/Grafana 端口 11176/11177/11178/11179 改为 21276/21277/21278/21279，原镜像、环境变量与数据卷保留，原配置私密备份保存在 `qa/verification/day30/legacy-port-recovery/`。原 Day24 验收数字不变；本轮恢复结果见 [Day30 最终报告](day30-final-acceptance.md)。
