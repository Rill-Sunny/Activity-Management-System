# 白名单管理与资格查询

活动与场次各有独立的白名单配置。发布者只能管理自己活动及其所属场次的名单。接口要求显式 Bearer Token，账号为 `ACTIVE` 且角色为 `PUBLISHER`。

## 接口与请求

活动接口前缀为 `/publisher/activities/{activityId}/whitelist`，场次接口前缀为 `/publisher/sessions/{sessionId}/whitelist`。两组接口使用以下操作：

| 方法与相对路径 | 请求体 | 成功响应 | 含义 |
|---|---|---|---|
| `PUT` 前缀路径 | 无 | `200`，含 `configId`、`scopeType`、`scopeId` | 明确启用配置，初次创建为空名单 |
| `POST /members` | `{"userId":123}` | `201`，含 `memberId`、`userId` | 添加一个已存在的账号 |
| `DELETE /members/{userId}` | 无 | `204` | 删除该成员，保留配置 |
| `DELETE` 前缀路径 | 无 | `204` | 同一事务删除全部成员与配置 |

重复 PUT 返回同一配置，并保留已有成员。添加重复成员返回 `409`。配置存在时，重复删除同一成员返回 `204`。重复取消配置也返回 `204`。

新增成员只接收非空、正整数的 `userId`，拒绝额外字段。配置 ID、作用域和所属发布者由服务端确定。成员账号可以是 `USER` 或 `PUBLISHER`。

## 配置状态

删除最后一个成员后，配置仍存在，表示显式空名单，无人通过该名单。取消活动配置后，该活动恢复未配置状态；取消场次配置后，该场次恢复继承活动规则的状态。取消场次配置不删除活动配置或其成员。

成员管理要求先通过 PUT 创建配置。POST 和成员 DELETE 不隐式创建配置，配置不存在时返回 `WHITELIST_NOT_CONFIGURED`。

根据需求 QUAL-04，名单允许在活动发布后修改，不受核心配置的 `DRAFT` 限制。名单变更不撤销已成功预约或已支付门票。[配置与继承规则](schema.md#103-配置状态与资格语义)由统一的 `QualificationService` 实现。

## 统一资格查询

`QualificationService.canParticipate(Long userId, Long activityId, Long sessionId)` 返回布尔值。账号必须存在且为 `ACTIVE`，活动与场次必须存在，场次必须属于指定活动。ID 为空、非正数或上述校验失败时返回 `false`。`USER` 与 `PUBLISHER` 使用同一套参与规则。

通过基础校验后，名单按以下顺序判断：

1. 场次配置存在时，只检查当前账号是否属于该场次名单，直接返回结果。
2. 场次配置不存在时，查询活动配置。活动配置存在则只检查活动名单成员。
3. 两级配置均不存在时，名单规则为 `PUBLIC`，返回 `true`。

场次配置存在但名单为空，或名单非空但不包含当前账号，都返回 `false`。这两种情况均不回退到活动名单。场次成员通过资格判断时，也不要求账号同时属于活动名单。

`TicketOrderService.createOrder` 协调幂等重放与首次创建，首次创建由 `TicketOrderCreationService` 在创建事务中调用 `QualificationService.check(activityId, sessionId)`。该入口从 `CurrentUserProvider` 取得可信账号并使用同一套名单规则。`canParticipate` 保留为内部布尔查询，后续 `ReservationService` 复用业务资格入口。调用方另行校验发布状态、场次状态、开放窗口、限购和库存，资格通过不代表已取得可售资源。

每次业务动作在自己的事务中重新调用本服务。创建订单时通过资格检查，不能代替该订单未来支付时的检查；已支付门票和已成功预约也不会因后来名单修改而自动撤销。Day 12 已通过包含真实订单、Ticket、退款和预约的 [A–D 完整回归](../qa/reports/day12-whitelist-regression.md)。

相同创建请求的幂等重放只读取原订单，不新增参与行为，因此名单改变后仍可返回匹配的原单；账号仍须有效，也不能以重放结果代替未来支付时的资格复查。

资格检查使用 `FOR SHARE` 读取当前账号、父资源和适用名单，并通过 Activity 共享锁与名单管理的排他锁协调。独立调用使用 `READ_COMMITTED`，加入业务事务时锁保持到外层事务结束。多次资格检查可并行持有共享锁；即使外层已有旧快照，也不复用旧快照中的名单结果。具体先后顺序见 [资格读取范围](locking.md#qualification-reads)。

## 权限与事务

活动操作通过 `CurrentUserProvider` 取得可信账号，并核对 `activity.publisher_id`。场次操作先查询 `activity_session`，再查询其所属 `activity`，以所属活动的发布者作为权限依据。

每次管理操作在同一个事务中按 `activity` → `whitelist_config` → `whitelist_member` 的顺序加锁或写入。Activity 根记录也协调配置尚不存在时的并发管理操作。配置查询使用 `FOR UPDATE`，取消配置的两次删除一起提交或回滚。具体范围见 [白名单管理锁规则](locking.md#whitelist-management)。

成员新增和删除成功后，Service 显式更新配置的 `updated_at`。唯一约束继续阻止重复配置和重复成员。

## 错误响应

认证和角色错误沿用统一 JSON：`401 UNAUTHORIZED`、`403 FORBIDDEN`。业务错误使用包含 `code` 的 Problem Detail：

| HTTP 状态 | `code` | 原因 |
|---|---|---|
| `403` | `NOT_ACTIVITY_OWNER` | 当前发布者不拥有该活动或场次所属活动 |
| `404` | `ACTIVITY_NOT_FOUND` | 活动不存在，或场次所属活动不存在 |
| `404` | `SESSION_NOT_FOUND` | 场次不存在 |
| `404` | `WHITELIST_NOT_CONFIGURED` | 未启用配置就管理成员 |
| `404` | `WHITELIST_USER_NOT_FOUND` | 待加入账号不存在 |
| `409` | `WHITELIST_MEMBER_ALREADY_EXISTS` | 账号已在同一份名单中 |
| `400` | `INVALID_WHITELIST_REQUEST` | 成员请求不合法或路径参数格式错误 |

## 数据库与测试范围

应用运行需要先应用 [V3 迁移](../src/main/resources/db/migration/V3__create_whitelist_tables.sql)。[管理集成测试](../src/test/java/com/limitactivitysystem/whitelist/integration/WhitelistManagementMySqlIntegrationTest.java)要求配置真实 MySQL 连接和 `DB_PASSWORD`，通过 MockMvc 使用真实 Security 过滤链、Service 事务与 MyBatis。

该测试在专用连接上创建 V3 临时表。Service 事务实际提交或回滚，测试自身没有外围事务；用户、活动、场次 fixture 在测试结束后按自身 ID 清理。它不迁移现有业务库。

[资格单元测试](../src/test/java/com/limitactivitysystem/whitelist/service/QualificationServiceTest.java)无需数据库，验证继承、覆盖、不回退、账号与父子关系检查。管理集成测试同时验证真实配置与成员数据下的资格结果，以及名单修改提交后后续查询的结果。

[资格并发测试](../src/test/java/com/limitactivitysystem/activity/integration/WhitelistQualificationConcurrencyMySqlIntegrationTest.java)使用多个真实 MySQL 连接及独立随机 schema，验证名单修改与资格事务的协调，不提前实现支付或预约业务。

[名单变化集成测试](../src/test/java/com/limitactivitysystem/activity/integration/WhitelistChangeMySqlIntegrationTest.java)的十四个场景使用真实管理及消费 API，覆盖活动级与场次级名单。待支付单在成员移除后支付失败；已有 Ticket、Seat、Order 和预约保持有效；移除成员后仍可取消预约或退款，但再次预约或使用新请求键购票须通过当前名单。所有步骤复用同一买家 JWT，并执行资源快照比较与对账。

移除成员与取消配置分开验证：移除成员保留空名单，取消配置恢复活动级继承或公开规则。原订单创建请求的幂等重放只读取历史；即使返回 REFUNDED 订单，也不代表用户取得了新的参与资格。
