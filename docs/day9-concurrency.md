# Day 9 免费预约验收

本次完成 [schedule.md](schedule.md) 的 AT-01～AT-03，以及 Day 9 的业务矩阵、对账 SQL 和交付清单。测试入口为 [FreeReservationMySqlIntegrationTest](../src/test/java/com/limitactivitysystem/activity/integration/FreeReservationMySqlIntegrationTest.java)。

POST 请求由 MockMvc 执行，经过实际认证过滤器、Controller、Service、MyBatis 和 MySQL。测试创建随机隔离库，执行 V1、V3、V4 迁移，结束后删除该库。并发协调和故障注入均在测试源码中。

## 验收结果

| 场景 | 准备与执行 | 数据库最终状态 |
|---|---|---|
| AT-01 不同场次与有效唯一性 | 同一活动恰有三个场次；同一用户 POST 预约其中两场，然后分别重复预约 | 两场均 ACTIVE，各扣 1；两次重复都返回 409 ALREADY_ACTIVE，记录与名额不变 |
| AT-02 最后一个名额 | remaining=1；20 个不同 USER 账号同时 POST `/sessions/{sessionId}/reservations` | 1 个 201、19 个 409 QUOTA_EXHAUSTED；remaining=0、ACTIVE=1 |
| AT-03 取消后重新竞争 | 容量 1；预约→POST 取消→释放→原用户与另一账号 POST 竞争 | 分别验证原用户、另一账号胜出；旧行 CANCELLED，新行 ACTIVE、新 ID，remaining=0 |
| 同账号重复预约 | 同 userId、sessionId 的两个 POST；测试让预查询都返回空 | 1 个 201、1 个 409 ALREADY_ACTIVE；ACTIVE=1、remaining 只减 1 |
| 并发重复取消 | 两个 POST 同时取消同一预约 | 1 个 200、1 个 409 NOT_ACTIVE；预约 CANCELLED、名额只恢复 1 |
| startTime−1ms | 数据库时间固定为开始前 1ms，再 POST 取消 | 200；CANCELLED，返还 1 |
| startTime | 数据库时间等于开始时间，再 POST 取消 | 409 SESSION_STARTED；保留 ACTIVE 和原名额 |
| startTime+1ms | 数据库时间固定为开始后 1ms，再 POST 取消 | 409 SESSION_STARTED；保留 ACTIVE 和原名额 |
| 扣减后抛异常 | `takeOne` 已影响 1 行，INSERT 尚未执行 | remaining 恢复，预约未写入 |
| 取消状态变更后抛异常 | ACTIVE→CANCELLED 已影响 1 行，`restoreOne` 尚未执行 | 整笔回滚；预约仍 ACTIVE，名额保持原值 |

表中业务错误码省略 `RESERVATION_` 前缀；`SESSION_STARTED` 保留原名。故障测试还覆盖 INSERT 后、名额恢复后抛异常，共四处注入。每处均从事务外比较完整快照，并执行 [reservation_invariants.sql](../tests/sql/reservation_invariants.sql)。

20 个请求在各自事务首次取得名额锁之前通过同一个屏障，确认使用 20 个不同的数据库连接。该测试专用连接池为 22，保证所有请求均能进入屏障。

同账号测试让两个预查询都返回空，并断言两个事务都曾成功扣减。第二次 INSERT 由数据库 `uq_free_reservation_active(user_id, active_session_id)` 拒绝，其名额扣减随事务回滚。这验证了唯一约束承担最终保护。

取消仅在条件 UPDATE 成功影响 1 行后恢复名额。并发测试记录 `restoreOne` 的实际执行次数和影响行数，确认仅执行一次。边界测试通过 MySQL 会话 `SET timestamp` 控制数据库时间；业务 SQL 仍读取 `CURRENT_TIMESTAMP(3)`，事务结束后恢复会话时钟。

AT-03 的竞争测试先让一方持有名额事务，再通过 `performance_schema.data_lock_waits` 确认另一方实际等待同一名额行，随后提交胜方。两个参数分别选择原用户和另一账号先取得名额，验证原用户没有优先权。原预约 ID 的重复取消也不能释放新预约的名额。

## Day 9 必测矩阵

下表中的方法均位于预约 MySQL 集成测试类，参数化方法包含各个边界分支。111 项测试全部通过，其中预约业务 41 项、Schema 与对账 30 项、请求契约 40 项。

| 场景 | 已验证结果 | 测试方法 |
|---|---|---|
| PAID Session 尝试预约 | 400 ACTIVITY_NOT_FREE，无数据变更 | `activityTypeAndLifecycleAreCheckedBeforeQuotaChanges` |
| FREE + 未发布 | 409 ACTIVITY_NOT_PUBLISHED，无数据变更 | `activityTypeAndLifecycleAreCheckedBeforeQuotaChanges` |
| FREE + 窗口外 | 开放前或关闭时刻及之后返回 409 | `creationUsesExactDatabaseWindowBoundaries` |
| 白名单不通过 | 403 QUALIFICATION_DENIED | `whitelistChangesDoNotBlockCancellationButRebookingChecksCurrentEligibility` |
| 正常预约 | 201，立即 ACTIVE，扣 1 | `at01ThreeSessionsAllowTwoBookingsButRejectDuplicateActiveReservations` |
| 同用户同场再次预约 | 409，ACTIVE 仅 1，名额不再减少 | `at01ThreeSessionsAllowTwoBookingsButRejectDuplicateActiveReservations`、`databaseUniqueKeyRejectsConcurrentHttpDuplicatesEvenWhenThePrecheckMisses` |
| 同用户不同场次 | 同活动两场均成功 | `at01ThreeSessionsAllowTwoBookingsButRejectDuplicateActiveReservations` |
| 最后一名额多人抢 | 20 账号恰好 1 个成功，remaining=0、ACTIVE=1 | `at02TwentyDifferentAccountsPostingForTheLastSlotHaveExactlyOneWinner` |
| 开始前取消 | 开始前 1ms 返回 200，只返还 1 | `cancellationIsAllowedAfterSalesCloseButStrictlyBeforeSessionStart` |
| 开始后取消 | 开始时刻及之后返回 409 SESSION_STARTED | `cancellationIsAllowedAfterSalesCloseButStrictlyBeforeSessionStart` |
| 重复取消 | 只有首次成功，只返还 1 | `twoConcurrentCancellationsReleaseExactlyOneSlot` |
| 取消后重约 | 新 ID，重新检查资格、竞争名额，原用户没有优先权 | `at03CancellationAndRebookingKeepHistoryAndAnOldCancelCannotReleaseTheNewQuota`、`at03ReleasedSlotIsOpenToBothFormerOwnerAndOtherAccount` |
| 扣名额后异常 | 整笔回滚，恢复名额，无新增预约 | `failuresAfterEveryMutationRollBackQuotaAndReservationTogether` |
| 取消中异常 | 整笔回滚，保留 ACTIVE 和原名额 | `failuresAfterEveryMutationRollBackQuotaAndReservationTogether` |

## 预约对账 SQL

[tests/sql/reservation_invariants.sql](../tests/sql/reservation_invariants.sql) 包含五组只读查询，全部无返回行才通过：

1. 预约的 user、session、Activity 存在，且 Activity 为 FREE；覆盖 ACTIVE 和 CANCELLED 历史。
2. 同一 user + session 的 ACTIVE 数量不超过 1。
3. quota 关联有效 FREE 场次，容量快照等于场次容量，remaining 在合法范围内。
4. `activity_session.capacity = remaining + ACTIVE 数量 = quota.capacity`，取消历史不占名额。
5. ACTIVE 预约必须有 quota。

quota 按首次预约惰性初始化。尚未使用且没有 ACTIVE 的 FREE 场次可以没有 quota；该情况不报错。并发事务全部结束后执行这些查询，或在一个一致的只读快照内执行。

[FreeReservationSchemaMySqlIntegrationTest](../src/test/java/com/limitactivitysystem/activity/integration/FreeReservationSchemaMySqlIntegrationTest.java) 的 `reconciliationDetectsEachOrphanScopeAndQuotaViolation` 分别注入不存在的用户、场次、Activity，PAID 归属，缺少 quota，容量漂移和剩余量漂移，确认 SQL 返回目标违规。每项注入都回滚并再次验证零差异。

## Day 9 交付清单

| 输出 | 实际文件 |
|---|---|
| Reservation migration、Quota migration | [V4__create_free_reservation_tables.sql](../src/main/resources/db/migration/V4__create_free_reservation_tables.sql)，同一迁移建立两张表及 UNIQUE、CHECK、索引 |
| ReservationController | [ReservationController.java](../src/main/java/com/limitactivitysystem/reservation/controller/ReservationController.java) |
| ReservationService | [FreeReservationService.java](../src/main/java/com/limitactivitysystem/reservation/service/FreeReservationService.java) |
| ReservationMapper | [FreeReservationMapper.java](../src/main/java/com/limitactivitysystem/reservation/mapper/FreeReservationMapper.java) |
| Quota Mapper | [FreeSessionQuotaMapper.java](../src/main/java/com/limitactivitysystem/reservation/mapper/FreeSessionQuotaMapper.java) |
| DTO | [ReservationResponse.java](../src/main/java/com/limitactivitysystem/reservation/dto/ReservationResponse.java) |
| Qualification 集成 | 创建时调用 [QualificationService.check](../src/main/java/com/limitactivitysystem/whitelist/service/QualificationService.java)，重新预约重查当前名单 |
| Concurrency Tests | [业务与并发测试](../src/test/java/com/limitactivitysystem/activity/integration/FreeReservationMySqlIntegrationTest.java)、[Schema 与对账测试](../src/test/java/com/limitactivitysystem/activity/integration/FreeReservationSchemaMySqlIntegrationTest.java)、[请求契约测试](../src/test/java/com/limitactivitysystem/reservation/controller/ReservationRequestContractTest.java) |
| Invariant SQL | [reservation_invariants.sql](../tests/sql/reservation_invariants.sql) |
| API 文档 | [免费预约创建与取消 API](api/reservations.md) |
| State Machine | [免费预约状态机](state-machines.md#免费预约状态机)：ACTIVE → CANCELLED |

## 复跑

在项目根目录确认 MySQL 可连接，并设置当前进程的 `DB_PASSWORD`。然后执行：

```powershell
	powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tests\concurrency\run-day9.ps1
```

Maven 不在 PATH 时，使用参数指定可执行文件和本地缓存：

```powershell
	powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tests\concurrency\run-day9.ps1 `
	    -MavenCommand 'D:\Computer_Science\IntelliJ IDEA 2026.2.1\plugins\maven-plugin\lib\maven3\bin\mvn.cmd' `
	    -MavenRepository "$env:USERPROFILE\.m2\repository"
```

脚本执行预约业务、Schema 和请求契约三个测试类。缺少凭据、测试跳过、测试报告或证据未在本次运行更新时，脚本返回失败。当前应为 **111 项通过，0 失败、0 错误、0 跳过**。

本次记录位于 [verification/day9-acceptance](verification/day9-acceptance)。[day9-core-run-summary.json](verification/day9-acceptance/day9-core-run-summary.json) 包含 AT-01～AT-03 结论、三个测试类的统计、对账 SQL 的 SHA-256，以及原始 JSON、测试报告的 SHA-256。复跑后，新记录写入 `target/verification`，测试报告写入 `target/surefire-reports`。
