# Day 21 M3 回归记录

**后续补验（2026-11-15）：M3 通过。** Day 28 已完成真实 Redis/RabbitMQ 停机恢复、Confirm 后/ACK 前崩溃与幂等、Tempo 实际查询；全仓 AT-11～AT-15 及提交后数据库快照通过。最终全仓 1,788 项，1,787 通过、0 失败、0 错误、1 跳过；跳过的仅为历史保留演示库入口，本轮另行完成全部 14 份 SQL。详见 [Day 28 报告](day28-reliability-report.md) 和 [证据索引](day27-day28-evidence-index.json)。下方保留当时的执行记录，不用新计数覆盖历史结果。

更新日期：2026-11-10。最新全仓结果为 1693 项：1690 通过、0 失败、0 错误、3 跳过，BUILD SUCCESS。用户确认独立对账测试通过，22:08 生成的报告为 PASSED，全部 12 个 SQL 不变量差异为 0。今日任务 2～6 在暂缓实机停机测试后的范围内完成；两项真实停机不计为通过，Tempo 导出另行验证，M3 完整验收仍待补齐。历史记录保留在下方。

用户最新安排：实机停机测试暂时跳过，本次今日完成度检查排除这两项，不将跳过改记为通过。此前全仓失败已完成修复和复跑，对账入口也已通过，不再列为今日未完成项。Tempo 导出及完整 Observability 按后续计划推进；测试环境后台任务的日志问题单列清理。

基线 Git HEAD：`98aaa1d82a439fbd2efbb6505e2d3074a97150bc`。工作区包含此前未提交的 Day 19/20 代码和本轮修改，HEAD 不能单独代表待测代码。使用结果收集脚本保存源码摘要。

本轮补齐 AT-11 调整清单确认、AT-12 真实竞争成交后的回滚及迟到关闭保护、p16 夹具修复后对账、AT-13 两 SKU 累计限购、AT-15 三类商品混单隔离、Redis/RabbitMQ 真停机、Listener 停止恢复及 HTTP 至售后 Trace 测试。已有 AT-14、资格恢复、失败任务重放、ACK 重投和 DLQ 用例继续纳入验收。

静态检查发现并修正两类回归前置问题：业务测试的旧数据库版本缺少当前资格锁/Outbox 表，改为当前版本并保留独立历史迁移测试；Outbox 对账错误地将退款申请 ID 关联至订单，改为按事件类型检查来源。消息 SQL 与早期票务 SQL 分文件，避免 Phase 1 的零差异检查误读汇总结果或执行事务控制语句。

| 证据 | 实际结果 |
|---|---|
| 商品专项计数 | 用户反馈 total=127、passed=126、failed=0、skipped=1；被跳过的只有真实 Redis 停机方法 |
| AT-11～AT-14 | 对应商品专项场景通过，依据本轮完整测试类反馈 |
| AT-12 事务中间态和回滚最终态 | 真实竞争、整单回滚及迟到关闭保护用例通过；p16 的预期异常及夹具恢复检查包含在专项中 |
| AT-15、任务查询/重试/恢复 | 普通联动场景通过；失败重试和发货并发两项夹具修复已复跑通过 |
| Redis 旧值回填与失效补偿 | 对应普通回归用例通过 |
| Redis 实际停机/恢复 | 1 项被忽略；按用户要求暂缓，本次今日完成度检查排除，未执行 |
| RabbitMQ 消费者停止、ACK 重投、毒消息 | 最新全仓中 MQ 类 9 项：8 通过、0 失败/错误、1 真实停机跳过；消息积压与恢复快照已核对 |
| RabbitMQ 实际停机/补发恢复 | 按用户要求暂缓，本次今日完成度检查排除，未执行 |
| HTTP / Relay / Rabbit / Consumer / AfterSale traceId | 商品专项 Trace 用例通过；已有 JSON 记录同一 traceId 和不同业务 spanId |
| Tempo 导出和完整链路查询 | 待验收；已有日志仍报 4318 连接失败 |
| 全仓全量 tests / failures / errors / skipped / exit result | 1693 / 0 / 0 / 3，1690 通过，BUILD SUCCESS；数字退出码未打印，归档中为 null |
| 全部 SQL、Broker 快照与业务结果 | 独立 JUnit 已通过；保留演示库报告 PASSED，12 个 SQL 差异为 0；商品内部对账和 MQ 恢复快照均已核对 |
| 缺陷修复及复测 | 11 个测试类的修复已随最新全仓回归通过；此前失败记录保留在下方 |

已完成 Git 差异空白检查和结果收集脚本的 PowerShell 语法检查；最新修复的局部 Java 编译结果见文末。执行步骤、用例映射和结果收集见 [IDEA 回归交接](../guides/day21-regression.md)。编译及静态检查不能替代 IDEA 验证。取得真实证据后再更新本表和 schedule；不得因日历已进入后续日期而宣布 M3 完成。

## IDEA 首次专项运行反馈与修复

用户提供的两份日志显示 Java 23.0.1 启动成功，MySQL 迁移到 V16。日志没有提供可用于宣告业务验收通过的结果。

| 测试 | 日志根因 | 处理 |
|---|---|---|
| ProductCatalogMySqlIntegrationTest | 测试全局配置 `spring.rabbitmq.dynamic=false` 禁用自动创建 RabbitAdmin，但测试类仍强制注入该 Bean | 显式导入手动 RabbitAdmin 配置，禁止启动时自动声明；Rabbit 场景手动初始化 |
| RabbitMqOutboxReliabilityMySqlIntegrationTest | `requireEmptyMessagingQueues` 连接 `localhost:5672` 被拒绝 | 增加类级 AMQP 前置检查和明确的地址提示；必须提供可连接的 Broker 后复跑 |

新增 `RabbitMqTestPrerequisitesTest` 验证 Bean 可注入且不提前连接、连接成功后释放、连接失败保留根因及地址。测试代码待同事运行。Flyway 兼容性提示和 Mockito agent 警告不是这两份日志中的失败根因。

## 2026-11-10 再次运行反馈

| 项目 | 证据及结论 |
|---|---|
| 商品测试计数 | 用户反馈 total=127、success=117、failed=10；日志退出码 -1。未提供独立 skipped 计数和本轮源码摘要，不能代替全量测试报告 |
| 9 个 Rabbit 相关失败 | 8 个 AT-15/资格联动用例和 1 个 HTTP Trace 用例在 `prepareRabbitQueues` 连接 `localhost:5672` 被拒绝，未进入对应业务断言 |
| 第 10 个失败 | `realRedisStopPreservesTradesAndRecoveryDrainsDurableInvalidation` 缺少 `RUN_DAY21_FAULT_TESTS=true`，保护检查在停机前抛错；没有执行真实 Redis 停机 |
| IDEA 配置 | 两份日志启动命令均含 `-Djunit.jupiter.conditions.deactivate=org.junit.*Enabled*Condition`，绕过了环境开关条件。应删除该参数，显式设置所需环境变量 |
| MQ 可靠性类 | Spring 启动、迁移完成，`BeforeAll` AMQP 前置检查失败；退出码 -1，不能视作业务用例已经运行 |
| 本机 Broker | 已安装 RabbitMQ 4.4.0。Windows 服务 Running，端口 15672 可监听，但无 5672；Broker 日志反复 `BOOT FAILED: no_epmd_port`；节点 `rabbit@nohost` 中的 `nohost` 无法解析，EPMD 中有 rabbit/25672 注册记录 |
| Tempo | 日志存在 localhost:4318 连接失败；与上述 10 个失败分开记录，导出验收仍未完成 |

本轮新增本机节点解析修复脚本，并补充前置错误提示和 IDEA 分阶段运行说明；没有改变商品事务、退款幂等或 MQ 业务断言。普通联动回归和 Compose 真停机环境分别准备，未执行的场景不能计为通过。脚本路径与执行步骤见 [IDEA 回归交接](../guides/day21-regression.md)。

本机修复执行结果：经用户授权，以管理员 PowerShell 7 执行脚本，退出码 0。hosts 原文件已备份至 `C:\Windows\System32\drivers\etc\hosts.rabbitmq-20261110-133231-816.bak`，新增 `127.0.0.1 nohost`，重启现有 RabbitMQ。服务日志在 13:32:42 确认 IPv4/IPv6 的 5672 监听启动，在 13:32:43 确认 `Server startup complete; 3 plugins started`。没有安装依赖、改名节点或清理消息数据。

连接恢复后，服务日志显示当前客户端的 `local-dev` 账号认证失败，仍需同事在两个 IDEA 配置中同步已有 Broker 的实际账号密码、删除条件停用参数后复跑。原生 Windows 服务不能替代 Compose 真停机环境。本轮仅检查脚本语法、Git 差异空白及服务恢复；没有启动 Spring/Maven 测试，117/127 是用户此前提供的结果，不能作为修复后通过数。

## 2026-11-10 14:28/14:29 连接成功后的失败与修复

| 最新日志证据 | 原因与处理 |
|---|---|
| 商品测试两处事件 ID 看起来相同却断言失败 | `deliver` 直接比较原生 AMQP `LongString` 与 Java `String`。改用 Spring `DefaultMessagePropertiesConverter`，统一转换消息属性后比较并交给消费者 |
| 后续 7 个商品用例提示 `las.notification.refund` 有 1 条消息 | 前序失败未完成退款通知消费，且共享固定队列使失败传播。改为每类每轮随机命名的真实持久队列，结束时保存快照并清理本轮资源 |
| MQ 类 9 个用例在 `BeforeEach` 提示同一退款队列非空 | 日志明确显示 guest 成功建立连接；失败发生在共享队列前置检查。MQ 类现使用独立于商品类和主应用的随机前缀 |
| 商品真实停机测试仍缺少明确开启变量 | 两份启动命令仍包含 `-Djunit.jupiter.conditions.deactivate=org.junit.*Enabled*Condition`。删除该选项并按环境设置开关；保护检查保持有效 |
| localhost:4318 连接失败 | Tempo 导出告警单独记录，不能据此认定 RabbitMQ 连接失败；Trace 导出仍待验收 |

同时检查并修正了缓存通道关闭方式：测试辅助代码使用 `RabbitUtils.setPhysicalCloseRequired` 后再关闭 Channel，确保未 ACK 的消息真的触发重投，而不是仅将通道退回 Spring 缓存。业务提交、幂等与 ACK 断言继续保留。

新增 `RabbitTestMessagesTest` 的两个用例及 `RabbitMqTopologyTest` 的两个参数场景，覆盖消息头转换和默认/隔离拓扑的绑定完整性。生产环境名称前缀默认为空，已有 `las.*` 拓扑保持原名；测试专属名称同时应用于声明、Outbox 发布、重试发布与 Listener。清理仅作用于经校验的本轮前缀，没有删除 Broker 中现存的公共队列或消息。

验证范围：核对了本地 Spring AMQP 3.2.12 源码中的转换和关闭 API，并完成 Git 差异空白检查；未安装依赖、未运行 Spring/Maven 或上述新增测试。需同事删除错误 VM 参数后，在 IDEA 运行新增测试及两个专项类，保留完整结果和 `target/day21/mq-queues/` 快照。

## 2026-11-10 最新 119/127、8/9 反馈与修复

用户确认 `RabbitTestMessagesTest` 全部通过；消息中重复列出了同一文件名，因此未据此推断其他单元测试也已通过。商品日志更新时间为 14:58，MQ 日志更新时间为 15:01；两份日志仍带有 IDEA 的 `-Djunit.jupiter.conditions.deactivate=org.junit.*Enabled*Condition`，退出码均为 -1。这是两个专项类的结果，不能代替全量测试。

| 失败范围 | 日志根因 | 本轮修改 |
|---|---|---|
| 商品 6 个售后/Trace/并发用例 | `QualificationAfterSaleTaskMapper.findContext` 联结任务表和订单表，`status` 未加表别名，MySQL 报 `Column 'status' in field list is ambiguous`；售后处理和失败记录都用此查询，任务因此留在 PENDING | 所有任务字段明确使用 `t.`；失败恢复用例额外验证订单 PAID、任务 FAILED，以及订单/买家/来源事件映射正确 |
| 商品 1 个重新购票后的迟到事件用例 | `completeNonQualificationOutbox` 将尚未投递的 REFUND_SUCCEEDED 也标成 SENT，后续等待退款队列时收不到对应消息 | 只忽略夹具的购票支付和过期事件，保留退款与资格事件；断言退款通知先 PENDING、后 SENT，并产生一条通知 |
| 商品 1 个 Redis 真停机用例、MQ 唯一失败的 Broker 真停机用例 | 未设置 RUN_DAY21_FAULT_TESTS=true，但 IDEA 绕过内置条件后仍执行，ComposeServiceFault 的保护检查拒绝操作 | 两个专项类改用项目环境条件及执行前检查，未开启时跳过；保留停机辅助类的显式检查和所有业务断言 |

`Pattern` 单类配置会切回 `Class`，此前通过切换测试类型避免 IDEA 参数的建议不适用于用户当前配置。本轮代码支持直接使用 `Class`，无需反复寻找由 IDEA 自动追加的 VM 参数。需要明确设置 RUN_DAY20_LINKAGE_TESTS、REDIS_TEST_ENABLED、RUN_MQ_RELIABILITY_TESTS 和需要执行的 Trace 开关；原生 Windows RabbitMQ 环境暂不启用 Compose 停机开关。

MQ 日志中的 `Notification user and content are required` 是毒消息用例的预期失败重试，不应删除该校验。Tempo 4318 导出失败仍单独列为链路导出待验收。没有通过扩大锁等待超时、丢弃售后任务或吞掉消息异常消除报错。

新增 `RequiresEnvironmentVariableConditionTest` 共 9 个调用，覆盖类/方法条件组合、缺失/空值/false/大小写和空白不匹配、正常开启、生命周期回调兜底。两个原有专项类的测试数量未增加，只加强了既有断言。真实停机未执行仍需在报告中列为 skipped/未执行；M3 的 Redis/MQ 真停机证据待隔离 Compose 环境验收，不能因普通业务测试通过而补记通过。

本轮验证：使用本机已有 JDK 23.0.1 和最新 IDEA 日志中的本地依赖 classpath，对上述 Mapper、两个专项测试类、ComposeServiceFault、环境条件注解/扩展及其单元测试共 7 个 Java 文件执行局部编译，参数含 `--release 23 -proc:none -implicit:none`，退出码 0。产物位于 `target/day21/compile-check-20261110/`，不覆盖 IDEA 的正常编译输出。Git 差异空白检查通过。本轮未安装依赖、未启动 Spring、未运行 Maven 或 JUnit，119/127 和 8/9 仍是修复前用户提供的结果；新增 9 个单元测试调用及两个专项类待同事在 IDEA 复跑。

## 2026-11-10 15:22/15:23 条件跳过反馈与配置修复

用户确认 `RequiresEnvironmentVariableConditionTest` 全部通过。商品的 21 个跳过原因与注解逐项对应：REDIS_TEST_ENABLED 为 11 项，RUN_DAY20_LINKAGE_TESTS 为 8 项，RUN_DAY21_TRACE_TESTS 和 RUN_DAY21_FAULT_TESTS 各 1 项。MQ 的 9 项全部由类级 RUN_MQ_RELIABILITY_TESTS 条件拦截。原配置在 `.idea/workspace.xml` 中只保存了两个密码变量，未保存这些开关。日志没有新的测试断言失败；缺失开关按设计跳过，不能删除条件或默认启用停机实验来消除 skipped。

本机已创建 `Day21-Product` 和 `Day21-RabbitMQ` 两个永久 JUnit 配置，均按 Class 运行，保留原有连接信息。商品启用 Redis、联动和 Trace，MQ 启用可靠性测试；两个配置的 RUN_DAY21_FAULT_TESTS 均明确为 false。文件位于 `.run/*.local.run.xml`，已加入 Git 忽略并验证原环境值复制一致，没有覆盖正在由 IDEA 管理的旧配置。复跑须从下拉框选择这两个新名称，详见 [执行交接](../guides/day21-regression.md)。

代码仅改进环境条件诊断，区分“未设置”“空值”“值不匹配”，提示在当前测试配置的 Environment variables 中检查，同时避免打印变量值。既有单元测试补充诊断断言，仍为 9 个调用，执行语义不变。售后 SQL、消息发布和所有业务断言保持不变。

商品日志仍有向 localhost:4318 导出 Trace 的连接失败，导出验收仍待完成。故障注入用例中的 MyBatisSystemException/HTTP 500 按原断言验证回滚，不等同于测试断言失败。被跳过的 AT-15 场景没有执行，因此不能用本次零失败宣称此前售后修复已通过。

本轮检查：两个新配置通过 XML 解析、Class 类型、开关值和原有连接值一致性检查，`git check-ignore` 确认两个文件均被忽略。环境条件注解、扩展及单元测试共 3 个 Java 文件使用已有 JDK 23 和本地依赖编译，退出码 0；Git 差异空白检查通过。未安装依赖，未启动 Spring、Maven 或 JUnit。诊断断言及新配置的实际运行由同事在 IDEA 复核。

## 2026-11-10 15:34～15:36 商品剩余两项失败

用户反馈 `RabbitMqOutboxReliabilityMySqlIntegrationTest` 通过。当前 MQ 日志文件仍是 15:22 的旧跳过记录，因此通过结论按用户反馈记录，不据此补填新的测试计数或停机结果。商品新日志中仅出现一条 RUN_DAY21_FAULT_TESTS 跳过提示；失败分别位于以下用例：

| 用例 | 日志证据与根因 | 本轮修复 |
|---|---|---|
| `consumerFailureLeavesTicketRefundCommittedAndTerminalTaskCanBeQueriedAndReplayed` | 15:34:58 首次注入失败后，下一次重试已经 SUCCEEDED。FailureObservation 会清除一次性故障点，但循环没有重新注入，继续修改成功任务的 next_attempt_at，触发 chk_ticket_qualification_task_schedule | 每次预期失败前重新注入；仅将上一次 FAILED 且仍可重试的任务提前到期。逐次核对失败次数、原因、调度时间、退款数量、库存和限购；第 5 次后验证自动扫描不会再处理，之后仍通过管理员接口重放 |
| `productShipmentAndQualificationAfterSaleSerializeOnProductOrder` | 15:35:00 发货 HTTP 返回 401。代码误调用 MockMvc 的 post(path, uriVariables...)，传入 publisher 和 Map 并不会设置身份或请求体，导致一直等不到订单加锁通知 | 使用已有 postJson 构造认证和 JSON 请求体。工作线程提前退出时唤醒主线程并报告根因；保留真实 InnoDB 锁等待检查，并在清理夹具前等待两个事务结束 |

没有放宽数据库 CHECK、删除业务断言或增加锁等待超时。修改范围是两个原有测试方法，测试总数不变。继续使用 `Day21-Product` 运行完整商品测试类，重点核对这两个方法，确认失败恢复和并发用例不影响其他夹具。

本轮 Trace 文件 `target/verification/day21-http-rabbit-aftersale-trace.json` 的数据库时间为 15:35:16.113，记录 HTTP 传入的 traceId 与 Consumer、AfterSale 的 traceId 相同，两个业务 Span 不同。日志仍报 localhost:4318 连接失败，不能据此声明 Tempo 导出验收成功。

IDEA 已将商品本地配置保存为 `.run/Day21-Product.run.xml`，补充该名称及对应 MQ 名称的 Git 忽略规则，保留现有配置内容。商品测试类使用已有 JDK 23 和本地 classpath 编译，退出码 0；未运行 Spring/Maven/JUnit、未安装依赖。本轮代码运行结果仍待同事反馈。

## 2026-11-10 商品 126 通过、1 项真实停机未执行

本节依据用户最新复跑反馈更新：商品专项 126 项通过、0 项失败、1 项忽略，共 127 项。被忽略的方法是 `realRedisStopPreservesTradesAndRecoveryDrainsDurableInvalidation`。前一轮失败的 `consumerFailureLeavesTicketRefundCommittedAndTerminalTaskCanBeQueriedAndReplayed` 和 `productShipmentAndQualificationAfterSaleSerializeOnProductOrder` 均包含在通过范围内。

MQ 普通可靠性测试已按用户反馈记为通过，`realBrokerStopRetainsCommittedOutboxAndRecoveryDeliversIt` 的真实停机验收仍待执行。当前仓库内商品报错日志仍为 15:36 的两项失败记录，MQ 日志仍为 15:22 的旧跳过记录，均不作为本次通过证据。最新结果的实际退出码和运行时 Git commit/源码摘要尚未提供，不推定为 0 或当前 HEAD，也不把本次专项计数写成全仓全量计数。

新增 [真实停机执行指南](../guides/day21-fault-tests.md)、`deploy/compose.day21-faults.yaml` 和 `deploy/.env.day21-faults.example`，供同事在已有 Docker 环境中执行。专用 Compose 使用项目 `las-day21-faults`，仅提供 Redis 16379、RabbitMQ 5673/15673，沿用已有部署文件中的镜像版本。MySQL 继续使用已验证的测试连接。指南提供两个单方法 IDEA 配置、显式停机开关、自动 stop/start 流程、验证标准和证据路径。

本轮只更新文档和准备隔离环境配置，没有安装 Docker 或依赖、下载镜像、启动/停止容器或执行 Spring/Maven 测试。当前终端 PATH 未发现 Docker CLI，Compose 的实际解析与容器启动仍需执行人员验证。两个停机方法成功后再补写通过记录；完成全仓全量、全 SQL 对账及其他剩余证据后再判定 M3。

文档与配置检查：已使用本地已有 YAML 解析库检查专用 Compose 的语法、两个服务名、独立端口、持久卷和镜像版本；指南中的 4 段 PowerShell 命令通过语法解析；本地文档链接、代码块及 Git 差异空白检查通过。这些静态检查不等同于 Docker Compose 实际运行。Tempo 导出继续单列待验证，完整 Observability 仍按 Day 25 推进。

## 2026-11-10 暂缓实机停机后的完成度检查

已核对当前工作区的执行证据：

- `target/verification/day16-product-order-reconciliation.json` 和 `day17-product-after-sale-reconciliation.json` 均于本日 15:45:59 更新，各记录 126 个用例。商品正常夹具的差异为 0，三个标记 corruptFixture=true 的受控异常用例按预期检出差异；售后 126 个记录的差异全部为 0。不能把这些受控异常误报为正常业务失败。
- 本日 15:45 的 Trace JSON 中，HTTP、Consumer 和 AfterSale 的 traceId 相同，Consumer 与 AfterSale 的 spanId 不同；发货与联动竞争证据已生成。
- MQ 的 listener-stopped 与 listener-recovered JSON 记录了同一批 3 个事件：停止时均为 SENT、消费记录及通知为 0；恢复后消费记录及通知均各为 1，与普通 MQ 测试通过反馈一致。
- `target/surefire-reports` 中有 56 个 XML，但本日更新数为 0，最新修改时间为 2026-11-02，且其中商品旧报告只有 76 项。不能把这些旧文件累计为今日全量测试通过。
- 未发现 `qa/verification/day21` 本轮全量结果归档，也未发现 `target/day21/reconciliation.json` 独立全 SQL 对账报告。

因此，排除用户已暂缓的两个停机方法后，今日仍需完成任务 6：由同事在 IDEA Maven 生命周期执行全仓 `test` 并保存统计、实际退出码和对应代码版本；对保留演示数据的隔离库执行 `Day21ReconciliationMySqlIntegrationTest`，保存全部 SQL 结果和异常为零的对账结论。商品单类 126 项通过及其内部对账不能代替这两项。本次仅检查和更新状态，没有运行 Spring/Maven 或停机实验。

## 2026-11-10 独立对账空库失败与全仓执行交接

用户新增的独立对账日志显示：16:05:54 MySQL 连接成功，随后 `SELECT COUNT(*) FROM product_order` 返回 0，在原第 41 行非空断言失败，IDEA 退出码为 -1。当前生成的 `target/day21/reconciliation.json` 仅为 `{ }`，没有执行 12 个对账脚本。这是演示数据前置条件不满足，尚不能判断演示库库存、限购或售后是否存在差异。商品专项使用并清理随机隔离库，不会给该对账库留下订单。

本轮修改仅涉及测试诊断和执行说明：

- 独立对账入口改用已有 `@RequiresEnvironmentVariable`，避免 IDEA 对原生 Enabled 条件的停用参数绕过显式开关。
- 保留非空断言，错误中增加实际数据库名称及 `SPRING_DATASOURCE_URL` 检查提示；报告新增 `_meta`，记录库名、订单数、脚本总数/完成数、状态和时间。空库记为 `PRECONDITION_FAILED`，执行异常记为 `ERROR`，差异记为 `INVARIANT_VIOLATIONS`。只有 SQL 全部完成且不变量差异为空才写 `PASSED`。未插入演示订单或放宽业务断言。
- [回归指南](../guides/day21-regression.md) 增加独立 Maven 配置、全部环境开关、JDK 23、离线 `clean test`、清理前证据备份、Start/Finish 采集以及独立演示库检查步骤。真实停机继续关闭，全仓测试暂不执行保留数据对账；后者单独验证。

后续由执行人员确认对账配置指向正确的演示库；若库为空，先通过业务 API 完成并保留演示数据，再串行执行独立对账和全仓回归。新增诊断代码仍待 IDEA 复验，不能把本轮修改记录为测试通过。未运行 Spring/Maven/JUnit、安装依赖或操作服务。

静态验证：使用本地 JDK 23 和已有依赖完成对账测试及环境条件类的 `javac --release 23` 编译，退出码 0；全仓执行步骤中的两段 PowerShell 通过语法解析，文档本地链接、代码块与 Git 空白检查通过。确认 `tests/sql` 当前有 12 个脚本。以上检查未连接数据库，也不代表新增测试已经通过。

## 2026-11-10 首次全仓失败分析与演示数据准备

用户提供 `FullSite报错日志.md`，结束时间为 17:00:19。本地 Surefire XML 与日志一致：1693 项，1568 通过、13 断言失败、109 执行错误、3 跳过。跳过的恰好是 Redis 停机、RabbitMQ 停机和独立演示库对账入口。已将不含凭据的逐例失败摘要保存到 `qa/verification/day21/fullsuite-20261110-170019-failed/summary.json`。日志未给出数字退出码，本轮没有补填或把当前工作区的源码摘要冒充运行时摘要。

| 根因 | 影响 | 本轮修改 |
|---|---|---|
| ActivityQueryService 新增 CacheAsideService 依赖，MVC 切片未提供 | ActivityQueryTest 16 项上下文错误 | 提供缓存替身并执行真实加载回调，保留查询映射和安全链断言 |
| 控制器测试只模拟了 Mapper/DataSource，真实缓存失效写入及扫描仍运行 | 4 个发布者控制器测试类：19 项错误、1 项失败 | 同时模拟 CacheInvalidationRecorder 和 CacheInvalidationDelivery，保留原事务、权限和校验断言 |
| V4/V7 历史迁移库没有 V13 的 cache_generation | FreeReservationSchema 30 项、GiftInventorySchema 44 项错误 | 在历史结构测试隔离当前缓存副作用；不升级其历史版本来绕过迁移断言 |
| Redis 短时防重提前返回 REQUEST_IN_PROGRESS，数据库并发实验无法观察到全部等待者 | TicketPayment 3 项、TicketOrderSmallConcurrency 6 项失败 | 仅在这两个 MySQL 锁实验中使防重替身放行，仍验证真实数据库等待、资源不变量和终态。生产支付行为及 Redis 专项测试不变 |
| 新资格 Guard 在限购计数锁之前串行化支付与退款 | TicketRefundProcessing 2 项失败 | 改为观察实际 user_activity_qualification_guard 等待，并增加 Guard 先于额度、额度先于库存的顺序断言 |
| 已启用 Trace，但旧日志断言要求不存在 traceId | ObservabilityTest 1 项失败 | 核对 traceId/spanId 的有效格式，同时保留凭据、令牌、请求参数不出现在访问日志中的检查 |

上述 11 个测试类已使用本地 JDK 23、现有依赖编译通过，未执行 JUnit 或 Maven。没有增加锁等待超时或删除库存/限购断言。

用户要求直接准备对账数据后，新增 `tests/concurrency/prepare-day21-demo.ps1` 与 `tests/fixtures/day21/PrepareDay21Demo.java`。已实际运行数据准备程序：创建独立库，应用迁移 V1、V3～V16，以 47 次真实 HTTP 调用创建商品和门票交易，并由真实 RabbitMQ Listener 消费事件。仅初始发布者角色授予使用种子 SQL，业务状态、库存、退款、Outbox 和消费记录均由业务代码提交。该程序不调用 JUnit/Maven，不安装依赖。

可用演示库为 `day21_demo_14635936a4c14006b343bb406b6b07ba`。准备及恢复记录最终为 `PREPARED`，退出码 0；已停止本次临时应用，保留数据库和独立命名的 Rabbit 资源。证据位于 `qa/verification/day21/day21_demo_14635936a4c14006b343bb406b6b07ba/`：

- `preparation.json`：3 个商品订单，4 个 Product、5 个 SKU；共享限购、合法部分回库、混合订单和持票变化过程；联动任务分别为 SUCCEEDED 和 WAITING_RETURN，后者库存未回补。
- 8 个 Outbox 事件全部 SENT，6 个即时事件已各有消费记录；另 2 个 ORDER_EXPIRED 是真实 15 分钟到期消息，保存于专属延迟队列。它们有明确来源、到期时间和队列快照，不能记为已消费或永久丢失。后续继续演示时须使用报告中的库名和 `rabbitPrefix`，到期通知不能覆盖已支付/已退款订单。
- `preparation-reconciliation.json`：临时应用停止后，在同一只读 REPEATABLE READ 快照中执行全部 12 个 SQL，不变量差异为 0。这是实际 SQL 核对结果，不等同于运行了独立 JUnit 类。
- `.run/Day21-Reconciliation.local.run.xml`：已填写保留库 URL、连接变量及对账开关，执行人员直接在 IDEA 选择 `Day21-Reconciliation` 运行即可。该文件与本地账号信息均被 Git 忽略，不在报告中展示密码。

准备程序首次将尚未到期的消息误判为超时，另一次在汇总时误用 SKU 列名，均已修正并保留诊断记录；最终通过恢复模式完成同一演示库的报告和 SQL 核对，没有重复创建该库内的交易。恢复模式只接受带本程序失败报告且业务流程已完成的专属库。

下一步由同事先运行 `Day21-Reconciliation`，再使用普通回归开关复跑全仓 `-o clean test`，记录真实退出码及新 RunId。全量通过前不宣布 M3 完成，实机停机继续暂缓。

## 2026-11-10 最终复跑与今日完成度

已核对用户提供的 `FullSuite报错日志.md`、本轮 Surefire XML 和独立对账 JSON。本次仅读取、归档并更新文档，没有修改代码、运行测试或操作数据库服务。

| 项目 | 本轮实际结果 |
|---|---|
| 全仓测试 | 20:59:20 结束，耗时 14:20；总数 1693，通过 1690，失败 0，错误 0，跳过 3，BUILD SUCCESS |
| 跳过范围 | Redis 真停机、RabbitMQ 真停机、单独执行的保留演示库对账入口；没有额外关键场景被忽略 |
| 独立对账 | 用户确认 JUnit 通过；22:08:06～22:08:07 的 JSON 为 PASSED，数据库 `day21_demo_14635936a4c14006b343bb406b6b07ba`，3 个商品订单，12/12 脚本完成，异常结果集及异常行均为 0 |
| 商品内部对账 | 商品与售后各 126 个记录；正常业务差异为 0，3 个 corruptFixture 用例按设计检出异常，未误报为正常业务失败 |
| 消费者恢复 | 同一批 3 个事件：停监听器时消费记录/通知各 0，恢复后各 1 |
| Trace | 本轮 HTTP、Consumer、AfterSale traceId 一致，Consumer 与 AfterSale spanId 不同；Tempo 接收端导出仍单列待验证 |
| 版本与归档 | Git HEAD `98aaa1d82a439fbd2efbb6505e2d3074a97150bc`；工作区有未提交实现，已保存运行后源码 SHA-256 与状态，不把 HEAD 单独当作全部被测代码 |

归档目录为 `qa/verification/day21/fullsuite-20261110-205920/`，包含 `results.json`、`source-snapshot.json`、独立对账、商品内部对账、MQ 恢复及 Trace 文件。提供的日志未显示数字退出码，因此该字段记为 null，退出结果按实际 BUILD SUCCESS 记录；源码摘要明确标注 POST_RUN，未伪造运行前记录或证明运行中源码未变化。

日志仍有两类非测试失败记录：缓存失效扫描访问模拟 DataSource，出现 `DataSource returned null from getConnection()`；Tempo 的 localhost:4318 接收端不可达。前者说明部分非缓存测试上下文的后台扫描隔离仍可完善，后者属于已单列的导出验证。本次没有通过删除日志、停用断言或修改生产代码掩盖这些记录，也没有把它们计为 Surefire 的失败或错误。后续可清理测试后台任务并按 Day 25 推进 Observability。

**结论：按用户暂缓 Redis/RabbitMQ 实机停机测试后的今日范围，任务 2～6 已完成。** 两项停机仍标为未执行，M3 原始完整通过标准尚未全部验收，不将今日完成等同于 M3 无条件通过。
