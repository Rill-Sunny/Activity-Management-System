# 免费预约 Schema 与名额事务契约

状态：Day 9 的 Schema 设计已冻结，创建与取消 API 已按事务契约接入。接口的账号、归属、窗口、场次状态、当前白名单及原子性均有自动化验证。请求和错误码见[免费预约 API](api/reservations.md)。

免费预约成功时直接占用一个名额，记录为 `ACTIVE`。它使用独立的预约域，不经过支付，也没有票单的 15 分钟到期规则。规范 DDL 位于 [V4__create_free_reservation_tables.sql](../src/main/resources/db/migration/V4__create_free_reservation_tables.sql)，需求依据为 RES-01～RES-04。

## free_reservation 保留每次预约

| 字段 | 类型 | 语义 |
|---|---|---|
| `reservation_id` | BIGINT 主键 | 一次预约的独立标识 |
| `user_id` | BIGINT NOT NULL | 当前账号的逻辑引用，业务入口从认证上下文取得 |
| `session_id` | BIGINT NOT NULL | FREE 场次的逻辑引用 |
| `status` | VARCHAR(20) | 仅 ACTIVE、CANCELLED，默认 ACTIVE |
| `created_at` | DATETIME(3) | 默认数据库当前时间，保留原创建时间 |
| `cancelled_at` | DATETIME(3) NULL | ACTIVE 必须为空，CANCELLED 必须非空且不早于创建时间 |
| `active_session_id` | BIGINT 虚拟生成列 | ACTIVE 时等于 session_id，CANCELLED 时为 NULL |

数据库约束为：

```sql
	active_session_id BIGINT GENERATED ALWAYS AS (
	    CASE WHEN status = 'ACTIVE' THEN session_id ELSE NULL END
	) VIRTUAL,
	UNIQUE (user_id, active_session_id)
```

MySQL 的 UNIQUE 索引允许多个 NULL，InnoDB 也支持虚拟生成列的二级索引，所以同一账号可保留多条 CANCELLED 历史，但最多保留一条同场次 ACTIVE。生成列由数据库随状态变化维护，不接受调用方手工写入。依据：[MySQL 8.4 CREATE TABLE](https://dev.mysql.com/doc/refman/8.4/en/create-table.html)。

| reservation_id | user_id | session_id | status | active_session_id |
|---:|---:|---:|---|---:|
| 101 | 7 | 20 | CANCELLED | NULL |
| 102 | 7 | 20 | CANCELLED | NULL |
| 103 | 7 | 20 | ACTIVE | 20 |

取消只更新当前行，保留 ID、用户、场次、创建时间和取消时间。重新预约 INSERT 一条新记录，不把旧 CANCELLED 行改回 ACTIVE，也不删除旧行。它必须重新检查当前资格并竞争实时剩余名额。相同账号预约另一场次不会冲突。

不能使用 `UNIQUE(user_id, session_id)`，因为它连已取消的历史也限制为一行；`UNIQUE(user_id, session_id, status)` 同样不合适，因为第二条 CANCELLED 历史仍会冲突。

当前预约查询使用 `user_id = ? AND active_session_id = ?`，命中有效唯一键；历史按 `(user_id, created_at, reservation_id)` 查询；场次有效人数与批量处理使用 `(session_id, status)` 索引。

## 有效生成键与独立当前表的取舍

| 方案 | 数据库保护 | 额外维护 | 本项目决定 |
|---|---|---|---|
| 单表历史 + 生成有效键 | UNIQUE 直接约束 ACTIVE | 一次状态变更自动改变索引键 | 采用，与 Ticket.active_seat_id 一致 |
| 当前表 PK(user_id, session_id) + 历史表 | 当前表主键约束有效身份 | 创建、取消须同步当前指针和历史，两表同事务 | 可行，本阶段无需增加这层同步 |
| Java 查询后判断是否已有预约 | 并发查询可能同时未命中 | 仍需数据库唯一约束兜底 | 不能独立承担唯一性 |

生成列采用 VIRTUAL，CASE 表达式简单，复用现有 Ticket 方案。有效索引仍维护生成键；VIRTUAL 不表示没有索引写入成本。

## free_session_quota 存储免费名额

| 字段 | 类型 | 语义 |
|---|---|---|
| `session_id` | BIGINT 主键 | 每个 FREE 场次最多一条名额记录 |
| `capacity` | INT NOT NULL | 已发布场次 capacity 的冻结快照，必须大于零 |
| `remaining` | INT NOT NULL | 当前剩余名额，数据库 CHECK 要求 0 ≤ remaining ≤ capacity |
| `created_at`、`updated_at` | DATETIME(3) | 数据库时间，计数变化时更新 updated_at |

`activity_session.capacity` 是发布配置，`free_session_quota.remaining` 是运行时计数。快照允许本表用 CHECK 防止超额返还；它与场次容量相等属于跨表约束，由初始化、发布冻结及对账共同保护。MySQL CHECK 不能查询其他表，不能把 FREE 类型、用户存在性或跨表容量相等写成单行 CHECK。依据：[MySQL CHECK 限制](https://dev.mysql.com/doc/refman/8.4/en/create-table-check-constraints.html)。

名额独立于 PAID/GIFT Zone、Seat、Ticket 和购票限购计数。FREE 场次没有这些资源，预约与取消仅操作预约记录和免费名额。

将 remaining 扩展到 activity_session 也能做条件更新，但现有资格检查先持有 Session 共享锁，再更新同一场次计数会涉及共享锁升级。独立名额表让配置／资格读取与名额写锁职责分开，避免为计数写入升级场次行锁；它仍有单场次热点竞争，需要统一事务和锁顺序。

## 名额初始化不覆盖运行库存

选择惰性初始化：首次创建预约的事务，在锁定并验证当前 FREE / PUBLISHED 场次和资格后，按已冻结的场次容量创建 quota。它不要求变更已有发布接口，也适用于 V4 前已经发布的 FREE 场次。

```sql
	INSERT INTO free_session_quota(session_id, capacity, remaining)
	SELECT s.activity_session_id, s.capacity, s.capacity
	FROM activity_session s
	JOIN activity a ON a.activity_id = s.activity_id
	WHERE s.activity_session_id = :sessionId
	  AND a.type = 'FREE' AND a.status = 'PUBLISHED'
	ON DUPLICATE KEY UPDATE session_id = free_session_quota.session_id;
```

重复初始化只做主键无变化的更新，不能重新写 capacity 或 remaining。不要用初始化语句的影响行数判断名额是否取得；是否成功占名额只看下一条条件扣减。

quota 缺失表示尚未初始化，不表示可以绕过库存。ACTIVE 预约存在时必须已有 quota；缺失应视为一致性错误。初始化、扣减、预约 INSERT 在同一业务事务中，失败整体回滚。capacity 从服务端已发布配置取得，不能由客户端提交。

## 原子扣减与历史同事务

名额扣减的核心 SQL 已通过真实 MySQL 并发验证：

```sql
	UPDATE free_session_quota
	SET remaining = remaining - 1
	WHERE session_id = :sessionId
	  AND remaining > 0;
```

只有 affectedRows = 1 才可继续 INSERT ACTIVE 预约；0 行时终止本次创建。不要先 COUNT 当前预约人数或 SELECT remaining，再由 Java 判断后无条件扣减。

创建事务契约为：

1. 从认证上下文取得用户，调用当前资格入口，校验 FREE、活动／场次生命周期、数据库预约窗口及场次未开始。
2. 在上述共享锁仍持有时初始化 quota，并执行条件扣减。
3. 等待名额锁后重新检查数据库时间，最终预约写入也保护窗口与场次开始边界；时间不再合法时回滚已扣名额。
4. INSERT 新的 ACTIVE 记录，交由有效唯一键阻止同账号同场次重复预约。
5. 任一步失败完整回滚，包括名额已扣后遇到唯一键冲突的情况。

Schema 测试验证计数、记录和事务原子性；创建 API 通过 FreeReservationService 实现第 1、3 步的账号、当前资格与时间校验。最终 INSERT 同时保护 FREE / PUBLISHED / SCHEDULED、预约窗口及场次开始，返回后再读数据库时间，避免语句等待跨过边界。

## 取消只返还一次

所有同场次写路径统一先取得 quota 写锁，再写 reservation。取消可以先普通读取预约确定作用域，但不能先锁 reservation 再反向等待 quota。

用户主动取消要求本人、ACTIVE、数据库当前时间早于场次开始。生命周期与账号锁在 quota 之前取得；取消不作为新的预约资格行为，不因名单移除而重新要求通过白名单。场次取消等系统动作按其专用生命周期处理。

在已经完成这些校验的事务中：

```sql
	SELECT session_id FROM free_session_quota
	WHERE session_id = :sessionId FOR UPDATE;

	UPDATE free_reservation
	SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP(3)
	WHERE reservation_id = :reservationId
	  AND user_id = :userId AND session_id = :sessionId
	  AND status = 'ACTIVE';

	-- Only when the preceding UPDATE affected one row:
	UPDATE free_session_quota SET remaining = remaining + 1
	WHERE session_id = :sessionId AND remaining < capacity;
```

状态更新 0 行时不返还名额。状态更新 1 行后，名额返还也必须影响 1 行，否则抛出异常回滚整笔取消。重放旧预约 ID 的取消不能影响新预约；取消后重新预约没有名额优先权。

历史保留是业务写入约定：取消不 DELETE，不覆盖旧创建时间，不复用旧行。状态 CHECK 检查行形态，不独立约束所有历史状态转换或阻止人工删除。测试仅在自己创建的隔离库中删除夹具。

## 对账与冻结验证

每个已初始化的 FREE 场次，在所有事务结束后应满足：

```text
	remaining + ACTIVE 预约数 = quota.capacity = activity_session.capacity
```

[reservation_invariants.sql](../tests/sql/reservation_invariants.sql) 检查逻辑孤儿与非 FREE 引用、重复 ACTIVE、容量快照与剩余范围、名额与 ACTIVE 数量差异，以及 ACTIVE 预约缺少 quota。所有查询均无返回行才通过。

[FreeReservationSchemaMySqlIntegrationTest](../src/test/java/com/limitactivitysystem/activity/integration/FreeReservationSchemaMySqlIntegrationTest.java) 在随机隔离库中执行 V1、V3、V4，避开带固定 USE 的 V2 seed。当前 30 项验证覆盖生成唯一键、多个取消历史、跨场次与跨账号、字段 CHECK、初始化竞争、不重置剩余量、最后名额竞争、重复预约失败回滚、两处事务故障及七类独立对账反向检测。

Schema 冻结阶段连同发布回归共 125 项通过，无失败、无跳过。该阶段的历史与并发记录、测试统计保存在 [verification/day9-schema](../qa/verification/day9-schema)。复跑选择器：

```powershell
	mvn '-Dtest=FreeReservationSchemaMySqlIntegrationTest,ActivityPublishingMySqlIntegrationTest,PublisherActivityPublishTest' test
```

测试按项目约定要求 DB_PASSWORD；没有凭据时 MySQL 用例会跳过，跳过不能算 Schema 已通过验证。

## API 与事务验证

[FreeReservationService](../src/main/java/com/limitactivitysystem/reservation/service/FreeReservationService.java) 的创建与取消入口均使用 REQUIRES_NEW / READ_COMMITTED，独立提交完整业务动作。调用方外围事务的回滚不会撤销已经完成的预约动作。所有名额和状态写 Mapper 要求已有事务。

数据库时间查询禁用 MyBatis 缓存，每次实际执行 `CURRENT_TIMESTAMP(3)`。创建在资格读取后、quota 锁等待后和最终 INSERT 后复核；取消在取得 quota 与预约锁后、最终取消写入及返还后复核开始时刻。

取消重复请求采用明确业务拒绝：第二次返回 409 RESERVATION_NOT_ACTIVE，资源不再变更。ACTIVE 唯一键冲突从事务代理向外抛出业务异常，已扣名额同时回滚。

[ReservationRequestContractTest](../src/test/java/com/limitactivitysystem/reservation/controller/ReservationRequestContractTest.java) 验证真实 MVC 和认证、两种消费角色、服务端字段保护、非法 ID 与正文及错误契约。[FreeReservationMySqlIntegrationTest](../src/test/java/com/limitactivitysystem/activity/integration/FreeReservationMySqlIntegrationTest.java) 使用真实 Service、Mapper 和随机隔离数据库，覆盖：

- 创建即 ACTIVE，名额立即减少；同活动不同场次可分别预约，重复同场拒绝；不产生付费资源。
- 本人取消、他人拒绝、重复取消不再返还；重新预约生成新 ID，旧 ID 的取消不影响新名额。
- 当前名单变化后仍能取消，重新预约必须重新检查名单，场次显式空名单继续覆盖活动名单。
- 创建窗口和取消开始时刻的精确毫秒边界，停售后但开始前可以取消；quota 锁等待跨界时整笔拒绝。
- 最终条件 SQL 与写入后的时间复核分别保护边界；扣名额、INSERT、取消状态、返还名额四处注入失败均完整回滚。
- 账号在认证后禁用时拒绝动作；名额返还影响零行时，取消状态也回滚。
- 最后名额竞争、同账号重复创建、并发重复取消；释放名额后原用户或其他用户均可取得新预约。

此前 API 验证记录保存在 [verification/day9-api](../qa/verification/day9-api)，其中公共池竞争保存两种胜方、旧历史 ID 和新预约 ID。验证后执行统一免费预约对账 SQL，确认剩余量与 ACTIVE 数量一致。

当前已完成 AT-01～AT-03、20 个不同账号同时 POST、跳过预查询后的唯一键保护、并发取消实际返还次数、毫秒边界和四处故障注入。复跑脚本、完整必测矩阵、111 项测试结果和交付清单见 [Day 9 免费预约验收](../qa/guides/day9-concurrency.md)。

此前 API 基线验证包含预约业务 39 项、请求契约 40 项、Schema 24 项；加入支付 84 项及安全 44 项后，共 231 项相关回归通过且没有跳过。历史 [run-summary.json](../qa/verification/day9-api/run-summary.json) 保存这五个测试类的结果及原始证据哈希；本次验收为预约业务 41 项、请求契约 40 项、Schema 与对账 30 项，合计 111 项。

复跑预约契约、业务和 Schema 的选择器为：

```powershell
	mvn '-Dtest=ReservationRequestContractTest,FreeReservationMySqlIntegrationTest,FreeReservationSchemaMySqlIntegrationTest' test
```
