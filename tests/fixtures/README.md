# Phase 1 固定测试世界

[phase1.json](phase1.json) 是 [Phase1JourneyMySqlIntegrationTest](../../src/test/java/com/limitactivitysystem/activity/integration/Phase1JourneyMySqlIntegrationTest.java) 的输入。测试使用随机隔离 MySQL 库，真实 Flyway 迁移到 V7。每个用例重新构建相同的逻辑世界，数据库 ID 全部从创建结果获得。

| 资源 | 固定配置 |
|---|---|
| 账号 | 2 个 Publisher、3 个 USER。发布者通过测试种子写入角色及密码哈希，普通账号走注册 API，五个账号全部走登录 API。 |
| PAID Activity | 一个活动，SELECT_SEAT、AUTO_ASSIGN 各一场。 |
| SELECT_SEAT | 容量 6、限购 4，四个 PAID Zone 分别定价 200、400、800、1200，各一席；一个 GIFT Zone 两席。 |
| AUTO_ASSIGN | 容量 8、限购 4，一个 PAID Zone 六席，每票 100；一个 GIFT Zone 两席。 |
| FREE Activity | 一个活动，两场各容量 2。 |
| 白名单 | PAID 活动发布后配置三个普通账号，场次未配置以验证继承；FREE 未配置，公开参与。 |
| 时间 | 从数据库当前时间取得基准，开售前移 10 天，停售后移 1 天，场次开始后移 20 天。 |

JSON 中的密码仅用于隔离测试账号，不是部署凭据。测试不保存登录 token 或数据库密码到验收证据。

## Messaging payload examples

`messaging/payment-succeeded.json` and `messaging/order-expired.json` show the durable envelope and payload shape consumed by the Rabbit listeners. Their IDs are documentation examples only. The real-service reliability test generates fresh event IDs, aggregate IDs and content, and removes only its own MySQL rows after execution.

订单、门票、退款和预约通过既有 API 构建，主链固定步骤如下：

1. `journey-four` 下单并支付四张不同票价的票，申请退第二、第四张，再处理原退款申请。
2. `journey-gift` 分配两份，读取原操作历史；另一账号 `journey-auto` 自动配座支付两张。
3. `journey-cancel` 取消待支付订单，`journey-expired` 在测试中将创建时间移至十六分钟前，调用既有过期关闭 Service，重复关闭无效果。
4. 用户 A 跨两个 FREE 场次预约，同场次重复申请拒绝；取消首场后，B 和 A 都可按公开规则参与。

每个阶段提交后，在一个只读 REPEATABLE_READ 快照执行 invariants、orphans、reservation_invariants、refund_invariants、gift_invariants 五个脚本。故障用例在真实 SQL 成功后抛异常，比较资源快照并执行同样对账。

AT-05 复用自动配座场，两单各三张并发支付。AT-07 通过相同创建 API 额外建立一场一席的 PAID 活动，两个买家竞争。测试不依赖应用库中已存在的账号、订单或固定数值 ID。

配置 DB_PASSWORD 及 Maven 后执行：

```powershell
	mvn '-Dtest=Phase1JourneyMySqlIntegrationTest' test
```

完整阶段回归使用 `mvn test`。数据库测试被跳过时不能记为通过，报告与修复记录见 [阶段测试报告](../../qa/reports/test-report.md)。

只演示 PAID → FREE → GIFT 三条连续流程时，执行 `mvn '-Dtest=Phase1JourneyMySqlIntegrationTest#demoABCRunsPaidFreeAndGiftContinuouslyAgainstCommittedDatabaseState' test`。手工 API 步骤、预期数据库终态及实测证据见 [Phase 1 Demo](../../docs/demo-phase1.md)。

## Day 26 固定版本验收夹具

`day26/acceptance-matrix.json` 将 20 个 AT 映射到真实方法和数据形状，`day26/risk-matrix.json` 补充高风险范围。测试方法自己的 `@BeforeEach` 创建业务数据，避免长期保留的数据影响本轮结论。

`Day26TestDatabase.java` 只允许在 MySQL 8.4.11 上创建或删除本轮 `day26_suite_<随机值>` 空夹具库。它使用 Flyway 执行业务迁移，排除仅含演示数据的 V2；应用部署库仍执行完整 V1～V18。不要把临时测试库名称改成开发数据库。

`Day26EvidenceExtension` 仅在专用运行器开启环境开关和 JUnit 扩展发现时启用。它在 `@AfterEach` 清理前读取已提交记录，执行全部 SQL，不写业务数据；缺少快照或存在正常不变量差异会阻止 AT 汇总通过。p16 的预期异常和恢复后的零差异分别保留。

运行与证据说明见 [Day 26 M5 报告](../../qa/reports/day26-m5-test-report.md)。授权前不执行构建、专项、全仓或跨实例测试；依赖未缓存时停止离线运行并确认下载范围。
