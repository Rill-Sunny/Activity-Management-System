# Smoke Test

## 1. 九张业务表与零物理外键

预期：九张业务表存在，使用 InnoDB，DDL 与 schema V2.2 一致，物理外键数量为 0。`flyway_schema_history` 不计入业务表。

执行 SQL：

```sql
USE limit_activity_system;

-- Expected: 9 business tables
SELECT COUNT(*) AS business_table_count
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_type = 'BASE TABLE'
  AND table_name IN (
      'app_user',
      'activity',
      'activity_session',
      'session_zone',
      'session_seat',
      'ticket_order',
      'ticket_order_item',
      'ticket',
      'user_session_purchase'
  );

-- Expected: 0 physical foreign keys
SELECT COUNT(*) AS physical_fk_count
FROM information_schema.table_constraints
WHERE table_schema = DATABASE()
  AND constraint_type = 'FOREIGN KEY';

-- Inspect actual DDL
SHOW CREATE TABLE app_user;
SHOW CREATE TABLE activity;
SHOW CREATE TABLE activity_session;
SHOW CREATE TABLE session_zone;
SHOW CREATE TABLE session_seat;
SHOW CREATE TABLE ticket_order;
SHOW CREATE TABLE ticket_order_item;
SHOW CREATE TABLE ticket;
SHOW CREATE TABLE user_session_purchase;
```

结果记录（用户确认符合预期）：

| 核对项 | 结果 |
|---|---|
| 业务表数量 | `business_table_count = 9` |
| 九张表 | `app_user`、`activity`、`activity_session`、`session_zone`、`session_seat`、`ticket_order`、`ticket_order_item`、`ticket`、`user_session_purchase` 均存在 |
| 引擎与 DDL | 均为 InnoDB，CHECK、UNIQUE、生成列及索引与 schema V2.2 一致 |
| 物理外键数量 | `physical_fk_count = 0`，业务表 DDL 无物理外键声明 |

结论：通过（用户确认）。

## 2. 原始前置数据 SQL

以下为原对话的前置 SQL。后续测试使用同一连接中的会话变量；原脚本区域总库存与初始余量均为 1。ST-01、ST-02 的日志不足以证明此前已完整执行该准备过程，详见第 4 节。

```sql
USE limit_activity_system;

-- Use UTC for DATETIME(3) test fixtures
SET @day1_old_timezone = @@session.time_zone;
SET SESSION time_zone = '+00:00';

START TRANSACTION;

-- 1. Create publisher
-- Test-only BCrypt-style fixture; not for authentication
INSERT INTO app_user
(username, password_hash, role, status)
VALUES (
    CONCAT('smoke_pub_', REPLACE(UUID(), '-', '')),
    '$2a$10$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW',
    'PUBLISHER',
    'ACTIVE'
);

SET @publisher_id = LAST_INSERT_ID();

-- 2. Create buyer
INSERT INTO app_user
(username, password_hash, role, status)
VALUES (
    CONCAT('smoke_buyer_', REPLACE(UUID(), '-', '')),
    '$2a$10$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW',
    'USER',
    'ACTIVE'
);

SET @buyer_id = LAST_INSERT_ID();

-- 3. Create paid activity
INSERT INTO activity
(publisher_id, name, type, status, open_time, close_time)
VALUES (
    @publisher_id,
    'Day1 Smoke Activity',
    'PAID',
    'DRAFT',
    UTC_TIMESTAMP(3) - INTERVAL 1 DAY,
    UTC_TIMESTAMP(3) + INTERVAL 1 DAY
);

SET @activity_id = LAST_INSERT_ID();

-- 4. Create session
INSERT INTO activity_session
(
    activity_id,
    start_time,
    end_time,
    capacity,
    status,
    max_tickets_per_user,
    seat_mode
)
VALUES (
    @activity_id,
    UTC_TIMESTAMP(3) + INTERVAL 2 DAY,
    UTC_TIMESTAMP(3) + INTERVAL 3 DAY,
    1,
    'SCHEDULED',
    1,
    'SELECT_SEAT'
);

SET @session_id = LAST_INSERT_ID();

-- 5. Create paid zone
INSERT INTO session_zone
(
    session_id,
    name,
    zone_type,
    price,
    total_amount,
    remains_amount
)
VALUES (
    @session_id,
    'Smoke Zone A',
    'PAID',
    99.00,
    1,
    1
);

SET @zone_id = LAST_INSERT_ID();

-- 6. Create seat
INSERT INTO session_seat
(
    zone_id,
    row_no,
    seat_no,
    allocation_priority,
    allocation_status
)
VALUES (
    @zone_id,
    1,
    1,
    1,
    'AVAILABLE'
);

SET @seat_id = LAST_INSERT_ID();

-- Publish the fixture activity
UPDATE activity
SET status = 'PUBLISHED'
WHERE activity_id = @activity_id;

-- Verify fixture references
SELECT
    @publisher_id AS publisher_id,
    @buyer_id AS buyer_id,
    @activity_id AS activity_id,
    @session_id AS session_id,
    @zone_id AS zone_id,
    @seat_id AS seat_id;
```

前置条件：六个 ID 均非 NULL 且对应记录存在。具体生成 ID 和准备阶段原始输出未提供，不另行填写。

## 3. ST-01：非法活动类型

预期：非法类型 `ABC` 被 `chk_activity_type` 拒绝，其余必填字段保持合法。

实际执行 SQL：

```sql
INSERT INTO activity
(publisher_id, name, type, status, open_time, close_time)
VALUES (
    @publisher_id,
    'Invalid Activity',
    'ABC',
    'DRAFT',
    UTC_TIMESTAMP(3),
    UTC_TIMESTAMP(3) + INTERVAL 1 DAY
);
```

原始输出：

```text
ERROR 1048 (23000): Column 'publisher_id' cannot be null
```

结果记录：实际因 `publisher_id` 为 NULL 触发非空错误，未取得目标 `chk_activity_type` 拒绝非法类型的证据。

结论：原执行失败，目标 CHECK 未验证。先核对发布者 fixture 与 `@publisher_id` 赋值，再使用原 SQL 补测。

## 4. ST-02：非法库存

预期：原 fixture 的 `total_amount = 1`，将 `remains_amount` 改为 2 时被 `chk_zone_inventory` 拒绝，原库存仍为 1。

实际执行 SQL：

```sql
UPDATE session_zone
SET remains_amount = 2
WHERE zone_id = @zone_id;
```

原始输出：

```text
Query OK, 0 rows affected (0.00 sec)
Rows matched: 0  Changed: 0  Warnings: 0
```

原对话的失败后核对 SQL：

```sql
SELECT total_amount, remains_amount
FROM session_zone
WHERE zone_id = @zone_id;
```

结果记录：UPDATE 匹配 0 行、修改 0 行、警告 0 条；失败后库存查询的实际输出未提供，不将预期的两个 `1` 记作实测值。

结论：原执行失败，目标 CHECK 未验证；本次结果不能证明库存 CHECK 失效。

### 失败原因

`Rows matched: 0` 明确表明 `WHERE zone_id = @zone_id` 未匹配目标区域，未发生库存更新，不能用本次结果判断 CHECK 是否拒绝越界库存。[MySQL 8.4 UPDATE 说明](https://dev.mysql.com/doc/refman/8.4/en/update.html)

极可能是 `@zone_id` 未初始化，或初始化与测试发生在不同连接中。MySQL 用户变量属于当前会话，未初始化时为 NULL，切换连接不会自动继承原会话赋值。结合 ST-01 的 `publisher_id=NULL`，两项结果共同指向前置 fixture 或会话变量缺失；现有日志尚不能确认唯一根因。[MySQL 8.4 用户变量说明](https://dev.mysql.com/doc/refman/8.4/en/user-variables.html)

其他可能包括准备失败、目标记录已回滚、变量为错误 ID，或数据库不同。原脚本明确将区域总量设为 1，因此在正确 fixture 上写入 2 应构成越界；若使用其他区域，还需核对该区域实际总量。

补测要求：在同一连接、同一数据库内确认 fixture 创建成功，变量非空且对应记录存在，再原样重跑 ST-01、ST-02，记录目标约束错误及失败后状态，最后回滚并复查清理。本次首次失败日志保留，复测结果另行追加。

## 5. ST-03：重复 buyer_id 与 request_id

预期：首次创建订单成功；相同 `(buyer_id, request_id)` 再次插入时，被 `uq_ticket_order_request` 拒绝，不新增重复订单。

执行 SQL：先插入第一笔合法订单，并记录其 ID 到会话变量。

```sql
SET @request_id =
    CONCAT('d1-idem-', REPLACE(UUID(), '-', ''));

INSERT INTO ticket_order
(buyer_id, session_id, request_id, total_amount)
VALUES (
    @buyer_id,
    @session_id,
    @request_id,
    99.00
);

SET @pending_order_id = LAST_INSERT_ID();
```

再次插入，保持 `buyer_id` 与 `request_id` 不变：

```sql
INSERT INTO ticket_order
(buyer_id, session_id, request_id, total_amount)
VALUES (
    @buyer_id,
    @session_id,
    @request_id,
    99.00
);
```

结果记录（用户确认符合预期）：

| 步骤 | 结果 | 数据状态 |
|---|---|---|
| 首次插入 | 成功 | 第一笔订单保留，`@pending_order_id` 对应此订单 |
| 相同组合再次插入 | 被 `uq_ticket_order_request` 拒绝 | 未新增重复订单，原订单未被覆盖 |
| 最终组合唯一性 | 符合预期 | 同一 `(buyer_id, request_id)` 仅保留第一笔订单 |

原始输出与错误码：未提供；以上为用户确认结果的文字记录，不代填具体订单 ID、重复键值或实测错误码。

结论：通过（用户确认）。组合唯一约束按预期阻止重复创建相同请求键的订单。

## 6. ST-04：订单 15 分钟有效期

预期：ST-03 创建的订单满足 `expires_at = created_at + 15 分钟`，差值为 900 秒，断言查询返回 `PASS`。

执行 SQL：查询 ST-03 保留的订单。

```sql
SELECT
    order_id,
    created_at,
    expires_at,
    TIMESTAMPDIFF(
        SECOND,
        created_at,
        expires_at
    ) AS duration_seconds
FROM ticket_order
WHERE order_id = @pending_order_id;
```

执行原对话的断言 SQL：

```sql
SELECT
    CASE
        WHEN expires_at =
             DATE_ADD(created_at, INTERVAL 15 MINUTE)
        THEN 'PASS'
        ELSE 'FAIL'
    END AS test_result
FROM ticket_order
WHERE order_id = @pending_order_id;
```

结果记录（用户确认符合预期）：

| 核对项 | 结果 |
|---|---|
| 查询目标 | ST-03 保留的订单，即 `@pending_order_id` 对应记录 |
| 创建与过期时间关系 | `expires_at` 等于 `created_at` 加 15 分钟 |
| `duration_seconds` | `900` |
| `test_result` | `PASS` |
| 生成列与类型 | `expires_at` 为 STORED 生成列，两列为 DATETIME(3)，毫秒保留 |

原始输出：未提供；具体订单 ID、创建时间、过期时间不另行编造。

结论：通过（用户确认）。订单过期时间按创建时间加 15 分钟生成。

## 7. ST-05：同座有效票冲突、退款后重发

预期：同一座位只能同时存在一张 VALID 票；原票退款后释放生成列唯一键，允许另一订单明细为同座签发新的 VALID 票，原退款票保留。

### A. 建立两笔订单及明细

执行 SQL：

```sql
-- First paid order
INSERT INTO ticket_order
(
    buyer_id, session_id, request_id,
    total_amount, status, paid_at
)
VALUES (
    @buyer_id,
    @session_id,
    CONCAT('d1-paid-a-', UUID()),
    99.00,
    'PAID',
    UTC_TIMESTAMP(3)
);

SET @order_a = LAST_INSERT_ID();

INSERT INTO ticket_order_item
(
    order_id, zone_id, allocation_mode,
    requested_seat_id, quantity, unit_price
)
VALUES (
    @order_a, @zone_id, 'SELECT_SEAT',
    @seat_id, 1, 99.00
);

SET @item_a = LAST_INSERT_ID();

-- Second paid order
INSERT INTO ticket_order
(
    buyer_id, session_id, request_id,
    total_amount, status, paid_at
)
VALUES (
    @buyer_id,
    @session_id,
    CONCAT('d1-paid-b-', UUID()),
    99.00,
    'PAID',
    UTC_TIMESTAMP(3)
);

SET @order_b = LAST_INSERT_ID();

INSERT INTO ticket_order_item
(
    order_id, zone_id, allocation_mode,
    requested_seat_id, quantity, unit_price
)
VALUES (
    @order_b, @zone_id, 'SELECT_SEAT',
    @seat_id, 1, 99.00
);

SET @item_b = LAST_INSERT_ID();
```

结果记录（用户确认符合预期）：两笔 PAID 测试订单及其明细均创建成功，明细指向同一测试区域与座位，各自 ID 保存到对应会话变量。

### B. 签发第一张 VALID 票

执行 SQL：

```sql
INSERT INTO ticket
(order_item_id, seat_id, paid_price, status)
VALUES (
    @item_a,
    @seat_id,
    99.00,
    'VALID'
);

SET @ticket_a = LAST_INSERT_ID();
```

结果记录（用户确认符合预期）：插入成功。原票状态为 VALID，`active_seat_id = seat_id`，该座位有一张有效票。

### C. 尝试签发同座第二张 VALID 票

执行 SQL：

```sql
INSERT INTO ticket
(order_item_id, seat_id, paid_price, status)
VALUES (
    @item_b,
    @seat_id,
    99.00,
    'VALID'
);
```

结果记录（用户确认符合预期）：被 `uq_ticket_active_seat` 拒绝。第二张有效票未写入，原票保留，该座位仍只有一张 VALID 票。

### D. 将原票改为 REFUNDED

执行 SQL：

```sql
UPDATE ticket
SET
    status = 'REFUNDED',
    refunded_at = UTC_TIMESTAMP(3)
WHERE ticket_id = @ticket_a;
```

结果记录（用户确认符合预期）：更新成功。原票为 REFUNDED，退款时间满足约束，`active_seat_id` 为 NULL；该座位此时没有 VALID 票。

### E. 为另一订单明细重新签发 VALID 票

执行 SQL：

```sql
INSERT INTO ticket
(order_item_id, seat_id, paid_price, status)
VALUES (
    @item_b,
    @seat_id,
    99.00,
    'VALID'
);

SET @ticket_b = LAST_INSERT_ID();
```

结果记录（用户确认符合预期）：插入成功。新票与原票 ID 不同，状态为 VALID，`active_seat_id` 等于该座位 ID；原退款票仍保留。

### F. 核对最终状态

执行 SQL：

```sql
SELECT
    ticket_id,
    seat_id,
    status,
    active_seat_id
FROM ticket
WHERE ticket_id IN (@ticket_a, @ticket_b)
ORDER BY ticket_id;
```

结果记录（用户确认符合预期；会话变量仅标识记录，不代填具体 ID）：

| 记录 | seat_id | status | active_seat_id |
|---|---|---|---|
| `@ticket_a` 对应原票 | 同一测试座位 | `REFUNDED` | `NULL` |
| `@ticket_b` 对应新票 | 同一测试座位 | `VALID` | 等于该座位 ID |

同座历史票数为 2，有效票数为 1。原始终端输出、具体票 ID 及唯一键冲突的实测错误码未提供，不另行编造。

结论：通过（用户确认）。有效票唯一约束、退款后的生成列变化及同座重发均符合预期。直接 SQL 修改仅验证数据库约束。

## 8. ROLLBACK 清理与复查

预期：在同一连接中回滚测试事务，恢复原会话时区；测试用户、门票计数均为 0，其他测试数据与修改恢复基线。

执行 SQL：

```sql
-- Roll back all Smoke Test fixture data
ROLLBACK;

-- Restore previous session time zone
SET SESSION time_zone = @day1_old_timezone;

-- Verify fixture rows are removed
SELECT COUNT(*) AS remaining_smoke_users
FROM app_user
WHERE id IN (@publisher_id, @buyer_id);

SELECT COUNT(*) AS remaining_smoke_tickets
FROM ticket
WHERE ticket_id IN (@ticket_a, @ticket_b);
```

结果记录（用户确认符合预期）：

| 核对项 | 结果 |
|---|---|
| 测试事务 | ROLLBACK 完成 |
| 会话时区 | 恢复测试前设置 |
| `remaining_smoke_users` | `0` |
| `remaining_smoke_tickets` | `0` |
| 其他事务内新增测试数据 | 已撤销，无测试数据残留 |
| 修改数据与原有非测试数据 | 与测试前基线一致 |
| 区域库存、座位状态与有效购票计数 | 恢复或保持基线 |
| 九张业务表及其约束 | 仍保留 |

原始输出：未提供；以上按用户对清理验收结果的确认记录，不补写终端日志。

结论：通过（用户确认）。测试数据已回滚，环境恢复基线；已分配的自增编号不要求回收。

## 9. Day1 验收结论

| 验收项目 | 结论 |
|---|---|
| 九张业务表与 DDL | 通过（用户确认） |
| 零物理外键 | 通过（用户确认） |
| ST-01：非法活动类型 | 原执行失败，目标 CHECK 待补测 |
| ST-02：非法库存 | 原执行失败，目标 CHECK 待补测 |
| ST-03：重复请求键 | 通过（用户确认） |
| ST-04：15 分钟过期时间 | 通过（用户确认） |
| ST-05：同座有效票冲突、退款及重发 | 通过（用户确认） |
| ROLLBACK 清理与复查 | 通过（用户确认） |

Day1 整体结论：待补测。ST-01 被非空错误提前拦截，ST-02 未匹配目标区域，两项目标 CHECK 尚未取得有效验证证据。

本次范围仅为数据库 smoke tests，不构成支付、退款 Service、库存联动或并发性能已通过验证的证据。
