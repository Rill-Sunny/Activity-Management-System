# 在 IDEA 执行 Day 21 回归

本文交接任务 2～6。JUnit/Maven 测试由同事执行；对账演示数据已按用户要求通过独立应用和真实业务接口准备。未安装新依赖或执行实机停机实验。缺少本地依赖或镜像时，先确认安装，再继续。

2026-11-10 最新结果：全仓 1693 项，1690 通过、0 失败、0 错误、3 跳过，BUILD SUCCESS；独立对账已通过，12 个 SQL、0 差异。商品类 126 通过/1 停机跳过，MQ 类 8 通过/1 停机跳过，另一跳过项是已单独通过的对账入口。暂缓实机停机后的今日任务已完成，无需仅为消除这 3 个 skipped 重新运行。完整 M3 尚保留真实停机验收，后续按 [真实停机执行指南](day21-fault-tests.md) 补测；本轮结果归档见 [回归报告](../reports/day21-m3-regression.md)。

## 本机选择已补齐开关的配置

2026-11-10 15:22/15:23 的日志显示商品测试跳过 21 项、MQ 测试跳过全部 9 项。检查本机 `.idea/workspace.xml` 后确认，原测试配置只保存了 `DB_PASSWORD` 和 `RABBITMQ_PASSWORD`，没有测试开关。商品的 21 项分别是 11 项 Redis、8 项资格联动、1 项 Trace、1 项真实停机；MQ 的整类开关缺失。退出码 0 不代表这些场景已经通过。

本机已生成两个独立配置，沿用原有连接信息，Test kind 均为 Class：

| IDEA 配置名称 | 本地文件 | 已设置的开关 |
|---|---|---|
| `Day21-Product` | `.run/Day21-Product.run.xml`（IDEA 已重命名保存） | `REDIS_TEST_ENABLED=true`、`RUN_DAY20_LINKAGE_TESTS=true`、`RUN_DAY21_TRACE_TESTS=true`、`RUN_DAY21_FAULT_TESTS=false` |
| `Day21-RabbitMQ` | `.run/Day21-RabbitMQ.local.run.xml` | `RUN_MQ_RELIABILITY_TESTS=true`、`RUN_DAY21_FAULT_TESTS=false` |

1. 在 IDEA 顶部运行配置下拉框选择 `Day21-Product`，点击该配置旁的运行按钮。
2. 商品测试结束后，选择 `Day21-RabbitMQ` 并运行。
3. 如果看不到新名称，保存当前文件并重新打开项目，再检查 **Run → Edit Configurations**。IDEA 支持将配置存为项目中的独立文件，见 [JetBrains 运行配置说明](https://www.jetbrains.com/help/idea/run-debug-configuration.html#share-configurations)。
4. 确认本次运行使用新名称。旧名称的配置仍保留原设置；从类旁按钮启动时需确认没有选回旧配置。
5. 记录 passed、failed 和 skipped。开关正确且前置环境可用时，商品应执行 126 项、跳过 1 项，MQ 应执行 8 项、跳过 1 项；实际是否通过以复跑结果为准。

两个本地文件包含已有连接信息，`.local.run.xml` 及 IDEA 保存后的 `Day21-Product.run.xml`、`Day21-RabbitMQ.run.xml` 均已加入 Git 忽略。其他机器可按上表创建配置并填写自己的连接信息。VM options 无需添加测试开关，也不必更改 JUnit 条件。新跳过提示会区分变量未设置、空值和值不匹配，并指向当前测试配置的 Environment variables。

此前失败的 `consumerFailureLeavesTicketRefundCommittedAndTerminalTaskCanBeQueriedAndReplayed` 和 `productShipmentAndQualificationAfterSaleSerializeOnProductOrder` 已随最新 126 项通过反馈完成复验。上表保留普通回归配置；补测真实停机时复制为专用配置，不直接改动日常运行目标。

## 准备隔离环境

1. 使用已有的专用 MySQL、Redis、RabbitMQ 和 Tempo。数据库账号需要创建和删除随机测试库的权限。不要连接业务环境。
2. 在实际使用的 IDEA JUnit 或 Maven 运行配置中设置 `DB_PASSWORD`、`SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`REDIS_HOST`、`REDIS_PORT`、`RABBITMQ_HOST`、`RABBITMQ_PORT`、`RABBITMQ_USER`、`RABBITMQ_PASSWORD`。主应用配置中的变量不会自动复制到测试配置。
3. 配置中的 MySQL 库必须存在，账号需要创建随机库的权限。商品测试、MQ 可靠性测试和大部分业务测试自行建库并执行当前迁移。V2 包含固定 `USE limit_activity_system`，这些隔离测试明确排除 V2。单独对账使用的演示库需要提前完成 V1、V3～V16。
4. 两个专项类自动生成各自的 Rabbit 名称前缀：`it.product.<随机值>.` 和 `it.reliability.<随机值>.`，交换机、队列、重试及死信路由均使用对应前缀。无需清空原有 `las.*` 队列。真实停机实验前仍须停止其他依赖同一 Broker 的应用。
5. 禁止并行运行测试类或多个 IDEA 测试进程。真实停机实验会影响同一 Redis 或 RabbitMQ 上的其他用例。

在 IDEA 的 **Run → Edit Configurations → Environment variables** 中填写测试开关。不要填到 VM options 中；`System.getenv` 不读取 `-D` 参数。两个专项配置均可使用 **Test kind = Class**，分别选择完整类名；不再要求切换 Pattern。若 VM options 中曾手动填写 `-Djunit.jupiter.conditions.deactivate=org.junit.*Enabled*Condition`，删除这一项即可，保留其他参数和环境变量。如果输入框没有这一项、启动命令中却出现它，则来自 IDEA 的测试启动行为，无需反复删除或切换测试类型。

2026-11-10 的修复将这两个类及独立对账类的环境条件改为项目自己的 `@RequiresEnvironmentVariable`。它同时检查类和方法所需变量，不受上述仅匹配 `org.junit.*Enabled*Condition` 的参数影响，并在执行前用 assumption 再次检查。缺少开关的用例记为跳过/未执行；已开启场景中的连接或业务失败仍然报错。其他测试类仍使用原有 JUnit 条件，不能给全量测试增加禁用条件的参数，参见 [JUnit 条件停用说明](https://docs.junit.org/5.12.2/user-guide/#extensions-conditions-deactivation)。

普通商品、缓存和 MQ 联动回归使用：

```text
REDIS_TEST_ENABLED=true
RUN_MQ_RELIABILITY_TESTS=true
RUN_DAY20_LINKAGE_TESTS=true
RUN_DAY21_TRACE_TESTS=true
RUN_DAY21_FAULT_TESTS=false
```

上述开关要明确填写，否则以前被 IDEA 强制运行的联动、缓存或 Trace 场景现在会按条件跳过。保留已有正确的数据库和 RabbitMQ 账号配置，尤其是 `RABBITMQ_PASSWORD`。VM options 无需添加用于启用测试的参数。Trace 开关仅在准备执行链路回归时设为 `true`；完整导出验收还需 Tempo 可用。

只有准备好与客户端地址对应的隔离 Compose 环境后，才额外启用真实停机实验：

```text
RUN_DAY21_FAULT_TESTS=true
DAY21_COMPOSE_FILE=<已有隔离环境 compose.yaml 的绝对路径>
DAY21_COMPOSE_ENV_FILE=<该环境 .env 的绝对路径>
DAY21_COMPOSE_PROJECT=<docker compose ls 显示的准确项目名>
```

Compose 文件中的 Redis 和 RabbitMQ 必须与上述客户端地址对应。`ComposeServiceFault` 只对已经运行的 `redis` 或 `rabbitmq` 执行 `stop`、`start`，不创建容器或拉取镜像。异常退出会尝试恢复服务。若 IDEA 强制终止进程，执行人员需手动恢复服务并检查健康状态。

Windows 原生安装的 RabbitMQ 可用于 MQ 联动、确认、重投和毒消息测试，但不能由上述 Compose 停机用例控制。尚未准备 Compose 时，真实停机用例应按条件跳过，报告中记为“未执行”，M3 仍待验收。不要仅为消除报错而设置 `RUN_DAY21_FAULT_TESTS=true`。

本项目已准备 `deploy/compose.day21-faults.yaml` 和对应环境文件示例，使用独立的 16379/5673/15673 端口。详细准备、IDEA Method 配置、执行和恢复步骤见 [真实停机执行指南](day21-fault-tests.md)。本轮仅提供文件，未安装或启动任何容器。

## RabbitMQ 已安装但连接仍被拒绝

2026-11-10 的本机排查发现：Windows 服务显示 Running，管理端口 `15672` 已监听，但 `5672` 未监听；服务日志反复出现 `BOOT FAILED`、`no_epmd_port`。节点名是 `rabbit@nohost`，本机却无法解析 `nohost`。Erlang 注册服务中存在 `rabbit` 的 `25672` 记录，因此需要先修复节点主机名解析。管理页面可访问不能代替 AMQP 就绪检查，参见 [RabbitMQ 网络排查](https://www.rabbitmq.com/docs/troubleshooting-networking)。

本机已在 2026-11-10 13:32 完成修复，日志确认 IPv4/IPv6 的 `5672` 监听已启动，随后输出 `Server startup complete`。无需重复执行。原 hosts 文件保存在 `C:\Windows\System32\drivers\etc\hosts.rabbitmq-20261110-133231-816.bak`。

仅当同一本机 `rabbit@nohost` 的映射再次缺失时，在管理员 PowerShell 7 中运行：

```powershell
.\tests\concurrency\repair-local-rabbitmq-hostname.ps1
```

脚本逐字节备份 Windows hosts 文件，增加 `127.0.0.1 nohost`，刷新名称缓存并重启已有 RabbitMQ 服务，随后检查 `5672`。它不安装软件、不更改节点名称、不删除 RabbitMQ 数据。备份路径会打印到控制台。其他主机或节点不要直接运行此修复。

端口恢复后，在两个 IDEA 配置中设置 `RABBITMQ_HOST=127.0.0.1`、`RABBITMQ_PORT=5672`，并填写该 Broker 实际存在的 `RABBITMQ_USER` 和 `RABBITMQ_PASSWORD`。13:32 的日志曾报出 `local-dev` 认证失败；14:28/14:29 及后续测试日志已确认使用 `guest` 成功连接。当前 `application.yml` 默认用户名为 `guest`，密码从必填环境变量 `RABBITMQ_PASSWORD` 读取。密码只填在本地运行配置中，不提交到仓库。

`4318` 连接失败来自向 Tempo 导出 Trace，不是最新商品测试的 SQL/消息夹具错误或 MQ 停机开关错误的根因。Trace 验收仍需要可用的接收端；不要把导出失败记录成链路验收成功。

## 执行业务和故障回归

两类 Rabbit 测试显式导入 `IsolatedRabbitTestConfiguration`，由其导入 `ManualRabbitTestConfiguration` 并提供 `autoStartup=false` 的真实 `RabbitAdmin`。MySQL 商品测试启动时无需连接 Broker；涉及 Rabbit 的场景才检查连接并手动声明拓扑。MQ 可靠性类在 `BeforeAll` 检查连接，连接失败即终止该类的场景准备，不将缺少 Broker 记为故障实验通过。

测试通过 `messaging.rabbit.name-prefix` 自动隔离 Rabbit 资源，执行人员无需手动配置。用例结束时先停监听器，再记录队列快照并删除本轮专属队列和交换机；下一个用例重新声明。队列保持 durable，单个用例内部的 Broker 重启仍须保留消息。快照位于 `target/day21/mq-queues/<本轮前缀>/`，包括测试名、队列就绪消息数及消费者数。强制终止 JVM 可能留下带旧随机前缀的资源，后续运行使用新前缀，不会消费这些残留消息。

2026-11-10 的消息头类型修复新增 `RabbitTestMessagesTest`，验证原生 AMQP `LongString` 经 Spring 消息属性转换后能与 Outbox 的字符串 ID 相等。`RabbitMqTopologyTest` 验证默认及隔离名称下的全部绑定、重试和死信目标不会跨越命名范围。先在 IDEA 运行这两个测试，再运行两类集成测试。人工消费辅助方法使用同一转换器，并实际关闭通道，保留“提交后未 ACK”重投验证；不能将通道退回缓存当作消费者断线。

最新 119/127 和 8/9 反馈对应的修复另新增 `RequiresEnvironmentVariableConditionTest`（9 个测试调用），验证环境条件缺失、显式关闭、正确开启，以及方法开关不能覆盖类级要求。它无需数据库、Redis、RabbitMQ 或 Spring 上下文，先在 IDEA 运行，再串行复跑两个专项类。商品联动用例同时增加“任务 FAILED、订单 PAID”的 Mapper 映射断言和“延迟资格事件的退款通知仍实际发布、只创建一条通知”的断言。真实停机开关为 false 时，两类各一个停机用例应跳过；跳过不计为验收通过。

若看到 `RabbitMQ test prerequisite failed at localhost:5672` 或 `Connection refused`，先确认 AMQP 服务地址。IDEA 与 Broker 在同一主机时使用其发布的端口；Broker 在远程机器时设置该机器的 `RABBITMQ_HOST` 和 `RABBITMQ_PORT`。`15672` 是管理端口，不能用于 AMQP。已有且停止的隔离容器可由执行人员启动；尚未安装 Broker、Docker 或镜像时，按本次约定先确认安装。

```powershell
Test-NetConnection -ComputerName <RabbitMQ主机> -Port <AMQP端口>
```

TCP 检查成功只说明端口可达；测试中的 AMQP 连接检查还会验证协议连接及认证。不要通过关闭必测开关或把异常改成跳过来宣称 M3 通过。

先在 IDEA 运行 `ProductCatalogMySqlIntegrationTest` 和 `RabbitMqOutboxReliabilityMySqlIntegrationTest`，失败时保留完整异常。使用现有 JDK 23。Maven 可用 `-o` 离线运行；若缺少依赖，不取消离线模式偷偷下载。

| 验收 | 重点用例或入口 | 必须确认 |
|---|---|---|
| AT-11 | `p04_previewReportsExactOutOfStockSkuAndDoesNotWriteResources` | 黑 M 不替换；原清单无法确认；调整后新单只含白 L |
| AT-12 | `at12RealCompetingSaleRollsBackEarlierSkuWithoutCorruptingTheLedger` | 真正成交耗尽 B；失败事务内 A 从 10 扣到 8、额度从 1 增到 4；失败后全部恢复，旧单 CLOSED/OUT_OF_STOCK |
| AT-12 关闭竞争 | `at12DelayedShortageClosureCannotOverwriteACommittedPayment` | 支付先提交时，迟到的关闭操作保留 PAID 与全部资源。该用例固定这个先后次序；并发重复支付另由 p18 覆盖 |
| 故意损坏夹具 | `p16_outOfStockAfterAnEarlierSkuUpdateRollsBackQuotaAndStockThenClosesTheWholeOrder` | 精确检出 B 的一条库存异常，恢复 B=4 后再次对账为空 |
| AT-13 | `at13OneOfEachSkuExhaustsTheSharedProductLimit`、p17 | A、B 各买 1 后，再支付任一 SKU 均失败；并发订单共享限购 2 |
| AT-14 | `specialTemplateBlocksOnlyNoReasonAndNonresalableReturnedGoodsDoNotRestoreStock` | 发货和签收不回库；验收失败、验收通过但不可再售均不回库；只有通过且可再售恢复库存 |
| AT-15 | `at15LastTicketRefundUsesRabbitOutboxAndOnlyRefundsQualifiedLineInMixedOrder` | 同单普通商品保留、失去资格的礼盒退款、其他场次商品按自身资格保留 |
| AT-15 边界 | `partialRefundWithAnotherValidTicketDoesNotStartQualificationAfterSale`、`delayedRefundEventRechecksEligibilityAfterAnotherTicketIsPurchased` | 仍有票不退；重新取得票后旧事件不误退 |
| AT-15 恢复 | `shippedQualifiedItemWaitsForReturnReceiptBeforeRefundAndRestock`、`consumerFailureLeavesTicketRefundCommittedAndTerminalTaskCanBeQueriedAndReplayed` | 发货商品等待退回；消费者失败不回滚门票退款；任务有限重试，ADMIN 可查和重放 |
| Redis 真停机 | `realRedisStopPreservesTradesAndRecoveryDrainsDurableInvalidation` | 读取回源；重复支付/退款资源仅变化一次；持久失效任务有错误原因，恢复后处理并收敛 |
| Redis 旧值回填 | `cacheGenerationPreventsOldMissValueFromBeingRefilledAfterCommit`、`failedCacheInvalidationStaysDurableAndScannerRetriesIt` | 保留 generation 竞争和失效补偿验证；模拟失败用例不能代替真停机 |
| Rabbit 真停机 | `realBrokerStopRetainsCommittedOutboxAndRecoveryDeliversIt` | Broker 停机时 Outbox 保留，恢复后 SENT，消费效果和记录各一次 |
| 消费者停机 | `stoppedListenerAccumulatesMessagesAndRealListenerRestartDrainsThem` | Listener 停止后积压三条；真实 Listener 启动后消费并提交 |
| 提交后未 ACK | `confirmedPublishLeaseExpiryAndConsumerRedeliveryKeepOneBusinessEffect` | 关闭未 ACK 的 channel；重投有 redelivered 标志；通知只有一条 |
| 毒消息 | `repeatedConsumerFailuresAreBoundedAndDeadLetteredWithQueryableReason` | 有限重试，失败原因可查，消息进入 DLQ |
| Trace | `httpRefundTraceReachesRealRabbitListenerAndAfterSale` | 真实 HTTP 请求→持久 traceparent→Relay→真实 Listener→售后；traceId 相同，Consumer 与 AfterSale 的 spanId 不同 |

Trace 测试启用真实随机端口和自动观测。用输出的 traceId 在 Tempo 检查 HTTP、`outbox.publish`、Rabbit listener、`qualification.after_sale`。代码断言通过和 Tempo 查询成功分别记录；只打印 traceId 不算完成后端导出验证。售后 span 覆盖处理逻辑，数据库提交在 Spring 事务拦截器返回时完成。

Redis 用例为顺序故障操作设置 30 秒上限，并保留实际耗时。这只验证本地故障恢复，不是高并发资源容量证明。服务恢复后另运行旧值回填竞争测试。

MQ 新增用例在清理测试数据前，将停机/恢复两阶段的 Outbox、消费记录、失败记录和通知写入 `target/day21/mq/`。Compose 命令日志位于 `target/day21/compose/`。将这些文件与本轮结果一起归档。

## 运行全量测试并记录结果

本轮范围为全仓普通回归。按用户要求，Redis 和 RabbitMQ 的两个真实停机方法继续暂缓；保留演示库的独立对账另行执行。仓库没有 Maven Wrapper，使用 IDEA 已有 Maven 和本机 JDK 23，无需先安装命令行 Maven。以下步骤适用于当前单模块 `pom.xml`。

### 1. 创建独立 Maven 配置

1. 停止正在运行的测试进程，确保专用 MySQL、Redis、RabbitMQ 正常运行。不要同时运行多个测试配置。
2. 打开 **运行 → 编辑配置 → ＋ → Maven**，名称填 `Day21-FullSuite`。这里使用 Maven 配置，没有 JUnit 的“测试种类”字段。
3. **工作目录**（有的布局先显示“项目”选择框）选择项目根目录 `limited-activities-system`，其中应包含当前 `pom.xml`。本机路径为 `D:\Computer_Science\aaaSaucedflavoredPancake\Backend_Study\Java\practice\limited-activities-system`。
4. **命令行** 填 `-o clean test`，不加 `mvn` 前缀。`-o` 使用本地依赖；`clean` 清理旧编译产物和旧 Surefire 报告，必须先完成下文备份。不要添加 `-Dtest=...`、`-DskipTests`、`-Dmaven.test.skip=true` 或忽略失败的参数；不要启用并行测试。
5. **Maven 主路径** 使用已有 Maven（例如“捆绑的 Maven 3”），**运行程序 → JRE** 选本机 `openjdk-23.0.1`。新版布局可能将 JRE 放在 **Java 选项** 中。若字段被隐藏，展开 **修改选项**；标为“从设置继承”的字段，可取消继承后只修改本配置。
6. **虚拟机选项** 可以留空；如果保留原有 JVM 参数，移除手动添加的 `-Djunit.jupiter.conditions.deactivate=...`，不要用它强行启用条件测试。确认 **跳过测试** 未勾选，**配置文件** 留空。
7. 在此 Maven 配置的 **环境变量** 右侧点击编辑按钮，打开表格，逐行添加下表中的“名称”和“值”。不要把整段变量文字放进“虚拟机选项”。连接信息不会从主应用或 JUnit 配置自动复制，需使用已经验证的值。填完依次点击 **确定 → 应用**。

中文名称已核对本机 IDEA 2026.2.1 的中文语言包。“Maven 主路径”也可在 **文件 → 设置 → 构建、执行、部署 → 构建工具 → Maven** 找到；其子项 **运行程序** 包含 JRE、虚拟机选项、环境变量和跳过测试。优先修改本次运行配置，避免影响其他配置。界面说明见 [JetBrains 中文 Maven 运行配置](https://www.jetbrains.com/zh-cn/help/idea/run-debug-configuration-maven.html)。离线模式若报告缺少插件或依赖，保存缺失项并先确认下载；不要直接取消 `-o`。

| 环境变量 | 值 |
|---|---|
| `DB_PASSWORD` | 已验证的测试 MySQL 密码，非空 |
| `SPRING_DATASOURCE_URL` | 已验证的专用测试库 JDBC URL，例如 `jdbc:mysql://127.0.0.1:3306/limit_activity_system?serverTimezone=Asia/Shanghai`；库必须存在 |
| `SPRING_DATASOURCE_USERNAME` | 对应测试账号，具备创建及删除隔离测试库的权限 |
| `REDIS_HOST` / `REDIS_PORT` | 已验证的测试地址；本机通常为 `127.0.0.1` / `6379` |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | 已验证的测试地址；本机通常为 `127.0.0.1` / `5672`，不是管理端口 `15672` |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | 已验证的 RabbitMQ 用户名和密码 |
| `REDIS_TEST_ENABLED` | `true` |
| `RUN_MQ_RELIABILITY_TESTS` | `true` |
| `RUN_DAY20_LINKAGE_TESTS` | `true` |
| `RUN_DAY21_TRACE_TESTS` | `true` |
| `RUN_DAY21_FAULT_TESTS` | `false` |
| `RUN_DAY21_RECONCILIATION_TESTS` | `false`，保留演示数据的对账另开配置执行 |

布尔值填写小写 `true` / `false`，不要加引号或空格。环境变量列表中的斜线表示两行独立变量。连接信息以已通过的专项配置为准；不要把示例库名当成自动创建库的命令。Trace 代码路径仍执行；Tempo 导出结果单独记录。

### 2. 记录代码并保留清理前的证据

保存代码后，在 IDEA 底部的 **终端** 中、项目根目录下执行下面的 PowerShell。每次复跑换一个新的 RunId，并在 Start、备份和 Finish 中保持一致。已有 RunId 会被拒绝，以免覆盖证据。

```powershell
.\tests\concurrency\collect-day21-results.ps1 -Phase Start -RunId day21-full-02

$archive = 'qa\verification\day21\day21-full-02\before-clean'
New-Item -ItemType Directory -Path $archive -ErrorAction Stop | Out-Null
foreach ($name in @('verification', 'day21', 'surefire-reports')) {
    $source = Join-Path 'target' $name
    if (Test-Path -LiteralPath $source -PathType Container) {
        Copy-Item -LiteralPath $source -Destination $archive -Recurse -ErrorAction Stop
    }
}
```

确认 Start 和复制均成功后再继续。`clean` 会删除 `target`，备份位于该目录之外。原始 XML 可能含环境凭据，备份只留在已被 Git 忽略的本地 `qa/verification`，分享前脱敏。若终端策略禁止脚本，按本机既有脚本执行流程处理，不用更改全局策略。

### 3. 执行并归档结果

在顶部选择 `Day21-FullSuite`，点 **运行**（绿色三角形），等待 Maven 结束。完整输出应包含测试统计及 `BUILD SUCCESS` 或 `BUILD FAILURE`；记录实际进程退出码。运行期间不要修改源码。结束后回到 **终端**，例如 IDEA 确实显示退出码 0 时执行：

```powershell
.\tests\concurrency\collect-day21-results.ps1 -Phase Finish -RunId day21-full-02 -ExitCode 0
```

若实际退出码为 1 或其他值，填写真实值。编译或依赖解析失败、尚未产生 Surefire XML 时，采集器会提示没有有效报告；此时保留 Maven 日志和退出码，修复后使用新 RunId，不能填成测试通过。

`qa/verification/day21/<RunId>/results.json` 保存总数、失败数、错误数、跳过数、退出码和逐例结果；通过数为 `tests - failures - errors - skipped`。Start 同时保存 Git commit、工作区状态及源码 SHA-256；Finish 检查源码是否变化和是否混入旧报告。当前工作区包含未提交实现，不能仅凭 HEAD 的 commit 判断被测内容。`suitePassed` 不自动批准 M3。

本轮允许跳过的只有两类停机方法和独立对账入口（当前各一个，合计三个；统计以实际报告为准）。逐项检查 Surefire XML 的 `<skipped>` 和条件原因；如果商品资格联动、Redis 普通回归或 MQ 整类被跳过，先修正环境配置后复跑，不能计为通过。`ProductCatalogMySqlIntegrationTest` 的 126 项通过只是其中一部分，不是全仓测试总数。

## 对保留的演示数据运行所有 SQL

**本机数据已准备，无需再手工造单。** 当前保留库为 `day21_demo_14635936a4c14006b343bb406b6b07ba`，对应配置已保存为 `.run/Day21-Reconciliation.local.run.xml`。在 IDEA 顶部选择 **Day21-Reconciliation → 运行** 即可；若名称未出现，重新打开项目后在 **运行 → 编辑配置** 检查。该配置含本地连接信息，已被 Git 忽略。

本次准备程序实际完成 47 次 HTTP 请求和真实 RabbitMQ 消费，保留 3 个商品订单、部分售后及 SUCCEEDED/WAITING_RETURN 联动任务。应用关闭后执行 12 个 SQL，差异为 0。结果索引为 `qa/verification/day21/latest-demo.json`，详细结果在该文件的 evidence 目录。后续独立 JUnit 已由同事运行通过，22:08 的 PASSED 报告另存于本轮全量归档，不以数据准备记录代替 JUnit 结果。

后续若要重新准备一套独立数据，可在项目根目录使用现有 JDK 和已编译工程执行下列数据准备程序。它新建专属库，保留旧库和报告，使用已有依赖；不执行全量测试。当前数据已准备完毕，无需重复执行。

```powershell
.\tests\concurrency\prepare-day21-demo.ps1 -JdkDirectory 'C:/Users/32170/.jdks/openjdk-23.0.1'
```

首次准备默认读取本地 `Day21-Product` 配置的数据库和 RabbitMQ 连接信息。其他机器可通过 `-ConnectionConfiguration` 指定自己的本地 XML 配置。`-CompileOnly` 只编译程序；汇总阶段失败时，`-ResumeSchema <本程序已生成的库名>` 仅恢复已完成业务流程的专属库，不重复创建订单。详细要求以程序诊断为准。

报告中 2 条 ORDER_EXPIRED 消息可能仍等待真实 15 分钟期限，这是延迟队列中的已记录消息；6 条支付/退款/资格即时事件已完成消费。等待退货的商品不会回库。以下手工步骤保留用于其他环境和排查。

### 本次失败原因与准备检查

2026-11-10 16:05 的日志显示 MySQL 已连接，但 `SELECT COUNT(*) FROM product_order` 返回 0；测试在原第 41 行的前置断言停止，尚未运行任何对账脚本。当时 `target/day21/reconciliation.json` 为 `{ }`。这不是已检出库存差异，也不是 RabbitMQ 连接故障。

`ProductCatalogMySqlIntegrationTest` 创建并清理随机隔离库，成功运行不会把订单留在主应用数据库。对账测试不会自动建库、插入订单或迁移演示库。按以下顺序检查：

1. 打开 IDEA 右侧 **数据库** 工具窗口，选择已有 MySQL 数据源。没有显示时，从 **视图 → 工具窗口 → 数据库** 打开。
2. 右键数据源，选择 **属性**，记下主机、端口和数据库名。此处只检查，不需要更改密码。它们应与主应用和后面的对账配置一致。
3. 右键数据源 → **新建 → 查询控制台**。在控制台上方选择准备对账的数据库；不是随机的 `qual_it_...` 测试库。
4. 粘贴下面三条查询，选中后按 **Ctrl+Enter** 执行，在下方结果页查看结果。[IDEA 查询控制台说明](https://www.jetbrains.com/zh-cn/help/idea/query-consoles.html)。

```sql
SELECT DATABASE() AS database_name;
SELECT COUNT(*) AS product_order_count FROM product_order;
SELECT status, COUNT(*) AS order_count FROM product_order GROUP BY status;
```

| 检查结果 | 下一步 |
|---|---|
| `database_name` 为 NULL | 控制台尚未选库；先选数据库，再执行 |
| `database_name` 与预期不同 | 检查数据源及控制台选库；后面的测试 URL 必须对应同一个主机、端口和库名 |
| 提示 `product_order` 不存在 | 选错库或迁移不完整；先确认已有演示库已执行商品相关迁移，不能直接继续 |
| `product_order_count=0` | 尚未留下演示订单，按下面步骤准备数据 |
| 数量大于 0 | 前置非空检查满足；还需确认这些是本轮已提交的演示业务数据 |

在已通过 Flyway 管理的演示库中，可另外执行 `SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;`，核对迁移记录到 V16 且成功。不要为了补一张表就单独执行片段 DDL。本项目 V2 含固定库名，另建演示库需要沿用已验证的迁移准备方式，不能只改 URL 后假定迁移完整。

若数量为 0，先核对 **对账测试本身** 的 `SPRING_DATASOURCE_URL` 是否指向已经做过演示的库。若现有库都没有数据，需要先通过应用 API 完成演示并保留订单。使用已迁移到 V16 的专用演示库启动应用，按 [账号、活动与门票准备](../../docs/demo-phase1.md)、[Product/SKU 管理接口](../../docs/api/products.md)、[商品订单及售后接口](../../docs/api/product-orders.md) 和 [资格联动说明](../../docs/messaging.md) 准备资源：

1. 发布者创建并发布活动、Product 和 SKU；普通商品和持票限定商品分别保留。
2. 买家调用 `/product-orders/preview`、`/product-orders/confirm`，用返回的真实 orderId 调用 `/product-orders/{orderId}/pay`，保存多 SKU 成交和共享限购结果。
3. 继续完成发货、收货、部分售后，以及最后有效门票退款后的联动；等待 Outbox、消费者和售后状态达到可解释的终态，保存本轮 ID。
4. 停止演示应用及其他写入进程，再运行下面的独立只读对账。不要直接 INSERT 一条订单、修改状态或删除非空断言来凑通过；单条未支付订单也不能证明 M3 连续演示已经完成。

目录 API 文档中早期“支付尚未实现”的进度描述属于历史内容；本次流程和验收状态以商品订单文档及本指南为准。

### 空库时，具体如何留下可对账的商品交易

下面演示先产生一笔多 SKU 成交，再对其中一项做售后。它用于建立可检查的数据；M3 的持票联动等场景仍需补齐，不能用这一笔订单替代全部验收。

1. 在 **运行 → 编辑配置** 中选择 `LimitActivitySystemApplication`，确认其 **环境变量** 中的 `SPRING_DATASOURCE_URL` 指向上面选定的演示库。保留已验证的数据库、RabbitMQ、Redis 和认证设置，正常启动主应用。此时先不要运行对账测试。
2. 使用演示库中已有的 ACTIVE 发布者账号和买家账号。分别调用 `POST /auth/login`，正文为 `{"username":"实际用户名","password":"实际密码"}`，从响应取得各自的 `accessToken`。发布者与普通买家不能混用；注册普通账号不会自动授予发布者权限。
3. 准备该发布者名下已发布的活动，记录真实 `activityId`。如果还没有活动或账号，先完成 [演示资源准备](../../docs/demo-phase1.md#准备本轮演示数据)，普通集成测试创建后即清理的账号和 ID 不能拿来使用。
4. 用已有 HTTP 客户端发送以下请求。也可在 IDEA 新建本地 `.http` 文件，按下方格式逐条执行。每步成功后记录响应 ID，再填下一步。密码和令牌只保留在本地。

HTTP 请求格式示例：以下 `{{publisherToken}}`、`{{activityId}}` 必须替换为本轮实际值，或在 HTTP 客户端中定义同名变量。接口有 JSON 正文时设置 `Content-Type: application/json`；其余发布、支付接口不发送正文。

```http
POST http://localhost:8080/publisher/products
Authorization: Bearer {{publisherToken}}
Content-Type: application/json

{
  "activityId": {{activityId}},
  "name": "Day21对账演示T恤",
  "description": "本轮演示保留数据",
  "qualificationType": "PUBLIC",
  "purchaseLimit": 2,
  "refundPolicy": "STANDARD_7D"
}
```

如果应用使用其他端口，以启动日志的实际端口为准。成功创建商品返回 `201`，记下 `productId`。接着执行：

| 顺序 | 使用身份 | 请求 | 正文及记录项 |
|---|---|---|---|
| 1 | 发布者 | `POST /publisher/products/{productId}/skus` | `{"name":"BLACK-M","price":99.00,"stock":10}`，记录返回的 `skuId` 为 A |
| 2 | 发布者 | 同上 | `{"name":"WHITE-L","price":99.00,"stock":10}`，记录返回的 `skuId` 为 B |
| 3 | 发布者 | `POST /publisher/products/{productId}/publish` | 无正文，确认商品为 `ON_SALE` |
| 4 | 买家 | `POST /product-orders/preview` | `{"items":[{"skuId":A,"quantity":1},{"skuId":B,"quantity":1}]}`，将 A/B 换成真实数字，确认 `available=true` |
| 5 | 买家 | `POST /product-orders/confirm` | `{"requestId":"day21-demo-buy-本轮唯一编号","items":[{"skuId":A,"quantity":1},{"skuId":B,"quantity":1}]}`，记录 `orderId` |
| 6 | 买家 | `POST /product-orders/{orderId}/pay` | 无正文，确认返回 `PAID`，SKU A/B 均剩余 9，同 Product 的购买计数为 2 |

同一业务请求重试保留 `requestId`；新一轮演示用新的值。不要向买家接口添加 `buyerId`、价格或 Publisher 字段。若创建后超过 15 分钟未支付，订单可能已过期，应重新确认新订单。

下面查出发货、收货和售后需要的 `orderItemId`。当前商品订单响应没有直接提供这个字段，不能把 `skuId` 当成明细 ID。把 `12345` 换成本轮 `orderId`，在同一演示库执行：

```sql
SELECT order_item_id, order_id, product_id, sku_id, quantity,
       fulfillment_status, refunded_quantity, restocked_quantity
FROM product_order_item
WHERE order_id = 12345
ORDER BY order_item_id;
```

找出 SKU A 对应的 `order_item_id`，记为 A_ITEM，再执行：

| 顺序 | 使用身份 | 请求 | 正文 |
|---|---|---|---|
| 7 | 发布者 | `POST /publisher/product-orders/{orderId}/ship` | `{"orderItemId":A_ITEM,"quantity":1}` |
| 8 | 买家 | `POST /product-orders/{orderId}/receive` | `{"orderItemId":A_ITEM,"quantity":1}` |
| 9 | 买家 | `POST /product-after-sales` | `{"requestId":"day21-demo-return-本轮唯一编号","orderId":ORDER_ID,"items":[{"orderItemId":A_ITEM,"quantity":1,"reason":"NO_REASON","simulateInspectionPassed":true,"simulateResalable":true}]}` |

A_ITEM、ORDER_ID 都要换成真实数字。步骤 9 模拟签收后的退回、验收和退款，响应应为 `SUCCEEDED`。本例只有 A 退款，B 保留成交：A 库存恢复为 10，B 仍为 9，Product 的 `valid_count` 从 2 降为 1；A 明细的 `refunded_quantity` 与 `restocked_quantity` 各为 1。步骤 7 发货后也应保存库存仍为 9 的基线；“已经申请售后但尚未退回”的状态需按下段联动流程进入 `WAITING_RETURN` 后单独核对，不能只凭发货后的库存推断。

完整 M3 演示还要保留持票限定交易：同买家先购买有效票，再购买同活动 `TICKET_REQUIRED` 商品，并保留普通商品。按 [门票退款接口](../../docs/api/ticket-refunds.md) 调用 `POST /ticket-refunds`，正文使用门票订单的 orderId 和 ticketIds；取得 `refundId` 后调用 `POST /ticket-refunds/{refundId}/process`，不发送正文。不要将商品 orderId 填到门票退款里。持两张有效票时先退一张，确认限定商品未退款；最后一张退掉后，等待资格事件消费和售后任务处理。未发货商品应自动模拟退款，已发货商品应进入 `WAITING_RETURN`。保存对应门票、商品明细、事件和任务 ID，并按 [联动说明](../../docs/messaging.md) 检查状态。

### 独立 IDEA 配置、执行与结果

1. 完成演示后，等待支付、退款、异步消费者和订单过期扫描完成，保存业务状态。可以在查询控制台运行 `tests/sql/messaging_reconciliation.sql` 检查事件和处理记录。`SENT` 仅代表发布确认，不代表消费成功；`WAITING_RETURN` 可以是等待人工退货的明确业务状态，不能直接改成成功。
2. 停止演示主应用及其他写入该库的程序。MySQL、Redis 和 RabbitMQ 服务保持运行；此操作不是实机停机实验。确认商品订单仍存在。
3. 打开 **运行 → 编辑配置 → ＋ → JUnit**，按下表填写。不要复制全仓 Maven 配置后直接运行。

| 中文配置项 | 填写内容 |
|---|---|
| 名称 | `Day21-Reconciliation` |
| 测试种类 | **类**，部分界面显示“测试类” |
| 类 / 测试类 | `com.limitactivitysystem.messaging.integration.Day21ReconciliationMySqlIntegrationTest` |
| 使用模块的类路径 | 当前 `limited-activities-system` 模块 |
| JRE | 本机 JDK 23 |
| 工作目录 | 项目根目录，即同时包含 `pom.xml`、`src`、`tests` 的目录 |
| 虚拟机选项 | 留空；不添加禁用 JUnit 条件的参数 |

4. 点击 **环境变量** 右侧编辑按钮。在此配置中填写以下变量；隐藏的字段可通过 **修改选项** 显示：

| 名称 | 值 |
|---|---|
| `SPRING_DATASOURCE_URL` | 演示主应用刚才使用的完整 JDBC URL，例如 `jdbc:mysql://127.0.0.1:3306/limit_activity_system?serverTimezone=Asia/Shanghai`；示例库名必须与实际一致 |
| `SPRING_DATASOURCE_USERNAME` | 该演示库的 MySQL 用户名 |
| `DB_PASSWORD` | 对应的 MySQL 密码 |
| `RABBITMQ_HOST`、`RABBITMQ_PORT`、`RABBITMQ_USER`、`RABBITMQ_PASSWORD` | 分别复制已有正确配置；`RABBITMQ_PASSWORD` 是应用配置的必填占位符 |
| `REDIS_HOST`、`REDIS_PORT` | 分别复制已有正确配置 |
| `RUN_DAY21_RECONCILIATION_TESTS` | `true` |
| `RUN_DAY21_FAULT_TESTS` | `false` |

其余 `RUN_MQ_RELIABILITY_TESTS`、`RUN_DAY20_LINKAGE_TESTS`、`RUN_DAY21_TRACE_TESTS` 和 `REDIS_TEST_ENABLED` 不控制这个测试，可以不设置。不要把环境变量放到“虚拟机选项”，也不要给值加引号。点击 **确定 → 应用 → 确定**。

5. 从顶部运行配置下拉框选中 **Day21-Reconciliation**，点击绿色 **运行** 按钮。此类只有 1 个 JUnit 方法；它在同一个只读 REPEATABLE READ 事务内执行当前 12 个 SQL 文件，因此正常显示 **1 项通过**，不是 12 项通过。若显示 1 项被忽略，检查本配置的显式开关。
6. 打开 `target/day21/reconciliation.json`。IDEA 左侧未显示 `target` 时，可用 **文件 → 打开** 选择该文件，或在文件资源管理器中打开项目目录。检查文件更新时间确实属于本轮，核对以下内容：

| 字段 / 结果 | 应满足的条件 |
|---|---|
| `_meta.database` | 等于演示库名 |
| `_meta.productOrderCount` | 大于 0 |
| `_meta.status` | `PASSED` |
| `_meta.totalScripts` / `_meta.completedScripts` | 当前均为 12 |
| `_meta.violationResultSetCount` | 0 |
| 每个 SQL 项中 `invariant=true` 的结果 | `rows` 全部为 `[]` |

`_meta` 是执行信息，不是 SQL 结果集。`messaging_reconciliation.sql` 的诊断结果可以有行。若状态为 `PRECONDITION_FAILED`，查看 `reason` 并补充演示数据；`ERROR` 表示执行未完成，结合异常与 `currentScript` 定位；`INVARIANT_VIOLATIONS` 表示有异常结果，查找 `invariant=true` 且 `rows` 非空的具体记录。旧 `{ }` 报告不能作为通过证据。

7. 将本轮 `reconciliation.json`、测试通过截图或日志、演示步骤与对应业务 ID 保存到 `qa/verification/day21/<本轮编号>/`。如果之后执行 `clean test`，先备份，否则文件会随 `target` 清理。保存 Rabbit 队列快照，按 `event_id` 对齐消息头 `x-event-id`、Outbox、`consumer_process`、通知或售后任务；DLQ、FAILED、WAITING_RETURN 要记录原因和下一步。

```powershell
docker compose --project-name <隔离项目名> --env-file <环境文件> -f <Compose文件> exec rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers
```

故意破坏库存的 p16 只在它自己的隔离库中允许一条指定差异，且用例恢复夹具后必须再次零差异。演示库对账不允许带着该异常通过。

## 定位锁等待失败

出现 `Lock wait timeout exceeded` 时，立即保存 `performance_schema.data_lock_waits`、`performance_schema.data_locks`、`information_schema.innodb_trx` 和 `SHOW ENGINE INNODB STATUS`。记录等待连接、持锁连接、SQL、测试方法和持有事务的调用栈。

旧的 Product 缓存回滚用例曾在外层事务写 Product 后，通过 REQUIRES_NEW 查询执行 `FOR SHARE`，形成自己等待外层事务。当前 `ProductQueryTransactionService.publicDetail` 已调用 `resolveForPublicRead`，Product 本身使用普通读取；保留 `productRollbackNeverCachesTheUncommittedName` 验证旧值可读且未提交值不进缓存。资格与交易路径仍使用锁。不要通过增加超时掩盖同类问题。

完成后把真实结果填入 [Day 21 报告](../reports/day21-m3-regression.md)。AT-11～AT-15、真实 Redis/MQ 故障、任务恢复、全量回归及对账证据齐全前，M3 保持待验收。
