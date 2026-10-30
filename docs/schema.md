# 限量活动预约与订单系统 — schema.md

> **版本：V2.5（增加退款申请、逐票金额与政策时间快照）**
>
> **数据库：MySQL 8.4 / InnoDB**  
> **需求基线：requirements.md Version 1.1（业务规则不变）**  
> **当前状态：15 张已设计表：1 张用户表 + 4 张活动／座位基础表 + 3 张票务交易表 + 1 张账号场次购票计数表 + 2 张白名单表 + 2 张免费预约／名额表 + 2 张退款申请／明细表；商品、赠票流水仍未设计**
>
> **核心决策：全部 15 张表不声明物理 FOREIGN KEY；保留逻辑关联、必要索引、唯一约束、`CHECK`、事务与原子更新。**

## 0. 架构决策：为什么改为逻辑关联

### 0.1 需要先澄清的性能事实

- InnoDB 检查外键时可能为父／子记录增加共享锁检查，确实可能引入锁竞争和额外开销，但**外键数量并不能直接推导出死锁或数据库崩溃**。
- 即使没有外键，热门演唱会大量请求同时扣减同一 `session_zone.remains_amount` 仍会竞争同一行的排他锁，这通常比静态基础表的外键更值得重点关注。
- MySQL 可以检测普通事务死锁并回滚其中一个事务。Service 必须正确处理回滚、超时和可控重试；不能将锁竞争等同于数据库进程崩溃。
- 这是为了学习高并发设计而作出的**可选架构取舍**，不是所有企业系统都必须遵守的“禁止外键”规范；单库单体系统保留外键也常常是合理选择。

官方参考：

- [MySQL 8.4：外键及锁行为](https://dev.mysql.com/doc/refman/8.4/en/ansi-diff-foreign-keys.html)
- [MySQL 8.4：InnoDB 死锁与处理](https://dev.mysql.com/doc/refman/8.4/en/innodb-deadlocks-handling.html)

### 0.2 新的职责边界

| 责任 | 承担方 | 要求 |
|---|---|---|
| 主键／唯一性 | MySQL `PRIMARY KEY`、`UNIQUE` | 不转交普通 Java 查询判断 |
| 单行数值范围与枚举值 | MySQL `CHECK`、`NOT NULL` | 保留必要数据库底线 |
| 表之间的关联存在性和业务归属 | Service 层 | 查询并校验 ID；新增子记录与关联记录变更在必要时使用同一事务 |
| 库存不超卖 | MySQL 条件 `UPDATE` + 事务 | 根据影响行数判断，禁止 `SELECT` 后无条件 `UPDATE` |
| 同一座位不可重复分配 | MySQL 座位行原子状态更新 + 事务 | `AVAILABLE → SOLD/GIFT_ALLOCATED`；退款按有效状态回退 |
| 账号／场次限购 | `user_session_purchase` 复合主键、原子条件 `UPDATE` + 事务 | 同一账号同场次的并发支付不能突破上限 |
| 重复请求幂等 | 持久化的唯一业务键／状态条件更新 | 多实例部署时不能仅靠 JVM `synchronized` |
| 引用异常与数据巡检 | 定期对账 SQL + 日志告警 | 无物理外键会增加孤儿记录风险 |

**保留逻辑关系，不等于取消数据库索引。** 不再有物理外键帮助自动创建索引，关联 ID 的常用查询索引应显式建立。

## 1. 逻辑关系总览（不声明物理外键）

```text
app_user.id                           ← activity.publisher_id
app_user.id                           ← ticket_order.buyer_id
app_user.id                           ← user_session_purchase.user_id
app_user.id                           ← free_reservation.user_id
activity.activity_id                  ← activity_session.activity_id
activity_session.activity_session_id  ← session_zone.session_id
activity_session.activity_session_id  ← ticket_order.session_id
activity_session.activity_session_id  ← user_session_purchase.session_id
activity_session.activity_session_id  ← free_reservation.session_id
activity_session.activity_session_id  ← free_session_quota.session_id（每场次一行）
session_zone.zone_id                  ← session_seat.zone_id
session_zone.zone_id                  ← ticket_order_item.zone_id
ticket_order.order_id                 ← ticket_order_item.order_id
ticket_order_item.order_item_id       ← ticket.order_item_id
session_seat.seat_id                  ← ticket.seat_id
activity.activity_id                  ← whitelist_config.scope_id（scope_type = ACTIVITY）
activity_session.activity_session_id  ← whitelist_config.scope_id（scope_type = SESSION）
whitelist_config.config_id            ← whitelist_member.config_id
app_user.id                           ← whitelist_member.user_id

activity (1) → (N) activity_session (1) → (N) session_zone (1) → (N) session_seat
ticket_order (1) → (N) ticket_order_item (1) → (N) ticket
(app_user.id, activity_session.activity_session_id) → user_session_purchase（账号 × 场次唯一计数）
```

- 上述 `←` 仅表示**逻辑引用关系**，不是 MySQL `FOREIGN KEY`。
- `activity_session.max_tickets_per_user` 存储**场次限购上限**，`user_session_purchase.valid_count` 存储**某账号当前有效持票数量**；二者职责不同。
- `activity_session.seat_mode` 决定场次选座规则；订单明细中的 `allocation_mode` 是下单时的模式快照。
- 支付前没有实际 `ticket`；待支付订单的选区、数量与指定座位保存在 `ticket_order_item`。

## 2. 用户身份与活动／座位基础表（5 张）

> 以下五条 `CREATE TABLE` 语句不声明物理外键，面向 MySQL 8.4 / InnoDB 新库建表。创建业务数据时，Service 仍必须校验逻辑引用与操作人身份。

### 2.0 `app_user` — 最小用户身份（V2.2 新增）

```sql
CREATE TABLE app_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    password_hash VARCHAR(255) NOT NULL COMMENT 'Stores a secure password hash; plaintext is forbidden',
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    CONSTRAINT uq_app_user_username UNIQUE (username),
    CONSTRAINT chk_app_user_username
        CHECK (CHAR_LENGTH(TRIM(username)) > 0),
    CONSTRAINT chk_app_user_password_hash
        CHECK (CHAR_LENGTH(TRIM(password_hash)) > 0),
    CONSTRAINT chk_app_user_role
        CHECK (role IN ('USER', 'PUBLISHER')),
    CONSTRAINT chk_app_user_status
        CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- **身份与权限**：一期一个用户仅有一个 `role`；`PUBLISHER` 可发布管理自己有权限的活动，也可参与普通购票行为。`DISABLED` 用户不得发起新的受保护操作，历史订单仍保留。
- **认证**：登录后的 `userId` 从可信身份上下文取得，不允许请求参数任意指定购买者／发布者；密码用 Spring Security `PasswordEncoder` 等安全方式哈希，不能自行使用明文、可逆加密或简单 SHA-256。
- **关联**：`activity.publisher_id`、`ticket_order.buyer_id` 与 `user_session_purchase.user_id` 都逻辑关联 `app_user.id`。业务删除账号应采用禁用／保留历史，不物理级联删除。
- **表名说明**：统一使用 `app_user`，避免旧稿 `user` 与 `publishers` 并存造成身份引用歧义。

### 2.1 `activity` — 活动

```sql
CREATE TABLE activity (
    activity_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    publisher_id BIGINT NOT NULL COMMENT 'Logical reference to app_user.id',
    name VARCHAR(50) NOT NULL,
    description VARCHAR(1000) NULL,
    type VARCHAR(5) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    open_time DATETIME(3) NOT NULL,
    close_time DATETIME(3) NOT NULL,

    CONSTRAINT chk_activity_type
        CHECK (type IN ('FREE', 'PAID')),
    CONSTRAINT chk_activity_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'FINISHED', 'CANCELLED')),
    CONSTRAINT chk_activity_open_window
        CHECK (open_time < close_time),

    INDEX idx_activity_publisher_status (publisher_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**逻辑关联：** `publisher_id → app_user.id`。创建活动前校验当前认证用户具备发布权限；不从请求体直接信任用户 ID。`PUBLISHED` 后的核心字段冻结、发布前配置完整性和取消流程由 Service 实现，必要时使用有状态条件的 `UPDATE`。活动不做级联物理删除。

### 2.2 `activity_session` — 场次

```sql
CREATE TABLE activity_session (
    activity_session_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL COMMENT 'Logical reference to activity.activity_id',
    start_time DATETIME(3) NOT NULL,
    end_time DATETIME(3) NOT NULL,
    capacity INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    max_tickets_per_user INT NULL COMMENT 'Per-account ticket purchase limit for paid sessions; NULL for free sessions',
    seat_mode VARCHAR(20) NULL COMMENT 'Seat selection mode for paid sessions; NULL for free sessions',

    CONSTRAINT chk_session_time
        CHECK (start_time < end_time),
    CONSTRAINT chk_session_capacity
        CHECK (capacity > 0),
    CONSTRAINT chk_session_status
        CHECK (status IN ('SCHEDULED', 'FINISHED', 'CANCELLED')),
    CONSTRAINT chk_session_purchase_limit
        CHECK (max_tickets_per_user IS NULL
               OR (max_tickets_per_user > 0 AND max_tickets_per_user <= capacity)),
    CONSTRAINT chk_session_seat_mode
        CHECK (seat_mode IS NULL
               OR seat_mode IN ('SELECT_SEAT', 'AUTO_ASSIGN')),

    INDEX idx_session_activity_time (activity_id, start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**逻辑关联：** `activity_id → activity.activity_id`。创建场次前检查活动存在、归属当前发布者、处于允许编辑的状态。正式发布前校验 `activity.close_time <= MIN(session.start_time)`，并保证活动下有至少一个场次。此跨表限制不依赖外键或单行 `CHECK`。

**V2.2 限购与选座模式（发布前跨表校验）：**

- `activity.type = 'PAID'`：所属场次 `max_tickets_per_user` 必须为正整数，`seat_mode` 必须为 `SELECT_SEAT` 或 `AUTO_ASSIGN`。限购上限不得超过场次总容量；是否超过实际**付费**区域的可售票数，还应在发布校验中检查。
- `activity.type = 'FREE'`：这两个字段均须为 `NULL`，免费预约遵循每账号每场次一个有效名额的既定规则。
- 发布后冻结场次 `seat_mode`、限购值和容量；场次时间仅能通过改期／取消专用流程变更。`CHECK` 无法查询父表 `activity.type`，以上校验必须由 Service 在发布事务中完成，并确保配置与发布状态变更不会竞态。
- 订单明细 `allocation_mode` 必须等于其所属场次的 `seat_mode`，且参与购票的 Zone 必须为 `PAID`；由 Service 在下单及模拟支付时校验。


### 2.3 `session_zone` — 付费／赠票区域与剩余库存

```sql
CREATE TABLE session_zone (
    zone_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL COMMENT 'Logical reference to activity_session.activity_session_id',
    name VARCHAR(100) NOT NULL,
    zone_type VARCHAR(10) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    total_amount INT NOT NULL,
    remains_amount INT NOT NULL,

    CONSTRAINT chk_zone_type
        CHECK (zone_type IN ('PAID', 'GIFT')),
    CONSTRAINT chk_zone_price
        CHECK (
            (zone_type = 'PAID' AND price > 0)
            OR (zone_type = 'GIFT' AND price = 0)
        ),
    CONSTRAINT chk_zone_inventory
        CHECK (
            total_amount > 0
            AND remains_amount >= 0
            AND remains_amount <= total_amount
        ),
    CONSTRAINT uq_zone_session_name
        UNIQUE (session_id, name),

    INDEX idx_zone_session_type (session_id, zone_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**逻辑关联：** `session_id → activity_session.activity_session_id`。仅为付费活动场次配置 Zone。创建区域前检查对应场次、活动类型和发布状态。`total_amount` 是规划座位数，`remains_amount` 是目前尚可分配数量。创建区域时由 Service 初始化两者相等，座位创建完成且区域数量满足规划后才能发布。付费和赠票区域独立，不得跨类型扣减。

**赠票占座约定：** 管理后台按数量分配赠票时，须按确定规则将同等数量的赠票区域座位从 `AVAILABLE` 变为 `GIFT_ALLOCATED`，并在同一事务内扣减该 GIFT 区域的 `remains_amount`；无需记录领取者的账号或身份。

**重要瓶颈：** `remains_amount` 是高并发写入热点。同一区域所有支付更新同一行，物理外键删除后这一热点仍然存在；如真实负载证明瓶颈，再评估限流、排队、库存分片等进一步方案，而非事先承诺能够支撑某个 QPS。

### 2.4 `session_seat` — 座位定义及当前占用状态

```sql
CREATE TABLE session_seat (
    seat_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    zone_id BIGINT NOT NULL COMMENT 'Logical reference to session_zone.zone_id',
    row_no INT NOT NULL,
    seat_no INT NOT NULL,
    allocation_priority INT NOT NULL,
    allocation_status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',

    CONSTRAINT chk_seat_row CHECK (row_no > 0),
    CONSTRAINT chk_seat_number CHECK (seat_no > 0),
    CONSTRAINT chk_seat_priority CHECK (allocation_priority > 0),
    CONSTRAINT chk_seat_allocation_status
        CHECK (allocation_status IN ('AVAILABLE', 'SOLD', 'GIFT_ALLOCATED')),
    CONSTRAINT uq_seat_zone_position
        UNIQUE (zone_id, row_no, seat_no),

    INDEX idx_seat_zone_availability_priority
        (zone_id, allocation_status, allocation_priority, seat_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**新增 `allocation_status` 的理由：** 原表只定义座位，不记录当前座位是否已被分配。为支持不依赖物理外键的原子占座，将座位当前占用状态作为此行的一部分：

- `AVAILABLE`：可以分配。
- `SOLD`：被有效付费门票占用。
- `GIFT_ALLOCATED`：被主办方赠票库存操作分配，不记录领取者平台账号。

座位历史归属仍须由后续 `ticket`、赠票库存操作记录等业务实体保存。**不要把 `allocation_status` 当作交易历史。** Service 必须校验：付费售票只能占用 `PAID` Zone 下的座位，赠票分配只能占用 `GIFT` Zone 下的座位。

## 3. 必须由 Service 维护的逻辑引用

| 写操作 | 需要检查的关联和约束 | 失败时行为 |
|---|---|---|
| 创建活动 | `publisher_id` 指向有效 `app_user`，角色为 `PUBLISHER` 且有发布权限 | 拒绝创建 |
| 新增场次 | `activity_id` 存在且归属发布者、活动允许编辑 | 拒绝创建 |
| 新增区域 | `session_id` 存在，场次所属活动为 `PAID` 且处于允许配置状态 | 拒绝创建 |
| 新增座位 | `zone_id` 存在，区域及所属活动允许修改座位配置 | 拒绝创建 |
| 白名单管理 | 作用域存在且归属当前发布者；成员账号存在；配置和成员变更在同一事务内完成 | 拒绝写入并回滚 |
| 发布活动 | 有效场次、时间、容量、Zone 座位数；PAID 场次合法限购与选座模式；FREE 场次配置为 NULL | 拒绝发布 |
| 模拟支付 | 用户 ACTIVE；订单、场次、区域、座位归属一致；订单模式匹配场次；限购计数原子更新；库存与座位满足条件 | 整笔支付事务回滚或按已确认业务规则关闭订单 |
| 门票退款 | 门票属于有效订单、未重复退款，关联座位处于合法占用状态 | 不重复释放库存 |
| 活动取消／改期 | 按专门状态机和处理记录执行，不能删除关联历史 | 记录失败并安全补偿／重试 |

**无物理外键的额外约定：**

1. 生产业务不直接物理删除有历史引用的活动／场次／区域／座位，使用既定的取消、结束或停用状态；未来确需清理数据，采用经校验的归档清理流程。
2. 由 Service 对逻辑引用进行校验，但**“先查询存在，再无条件插入／更新”本身不是并发安全保证**；操作必须与父记录的生命周期及其他竞争写入协调。
3. 不允许通过只改变 `xxx_id` 将已有订单／门票／座位任意迁移到别的场次；发布后静态配置冻结。
4. 批量导入、人工 SQL、迁移脚本不会自动经过 Service，因此须增加巡检 SQL 和操作权限控制。
5. `CHECK`、`UNIQUE`、原子 `UPDATE` 必须保留；不能因为取消 FK 就取消数据库数据完整性机制。

## 4. 高并发抢票的一致性操作（核心事务约束，非完整支付实现）

### 4.1 区域库存原子扣减

```sql
UPDATE session_zone
SET remains_amount = remains_amount - :quantity
WHERE zone_id = :zoneId
  AND zone_type = 'PAID'
  AND remains_amount >= :quantity;
```

`quantity` 必须为正整数。示例中 `:quantity`、`:zoneId` 等为业务参数占位符，实际 MyBatis 写法可用 `#{quantity}`、`#{zoneId}`，不可将带冒号的示例直接粘贴到 MySQL 客户端执行。只有影响行数等于 1，才表示本次条件扣减成功。不要先读取 `remains_amount` 再执行无条件扣减；即使没有外键，那样也会产生并发超卖风险。

### 4.2 具体座位的条件占用

```sql
UPDATE session_seat
SET allocation_status = 'SOLD'
WHERE seat_id = :seatId
  AND zone_id = :zoneId
  AND allocation_status = 'AVAILABLE';
```

必须确认影响行数为 1。选多座位时，按统一顺序对座位进行锁定／更新；如果任意一个座位占用失败，整笔事务回滚，包括前面的区域库存扣减。自主选座和自动配座均须遵守这一规则。

退款时只允许原本有效的门票从 `VALID` 转换为 `REFUNDED`，然后将相应座位由 `SOLD` 改回 `AVAILABLE` 并恢复对应区域库存；所有步骤应在事务中进行，重复请求不得多次恢复数量。

### 4.3 Service 事务示意

```text
@Transactional 处理模拟支付：
  1. 按稳定的锁顺序确认订单处于待支付且未超时（防止重复支付）
  2. 校验用户资格、活动／场次状态、商品或座位归属
  3. 初始化（如不存在）并原子更新 user_session_purchase 计数（见 §8）
  4. 按稳定顺序尝试原子扣减相关区域库存
  5. 按稳定顺序分配／条件占用具体座位
  6. 创建门票记录并更新订单为支付成功
  7. 提交事务；任何步骤失败均回滚本次交易写入
```

该操作顺序是**统一锁顺序的起点**，退款、取消、其他资金流转等并发路径必须一起设计并压测；不能只把这几个步骤包入 `@Transactional` 就假定完全不会死锁。

### 4.4 票务表与限购计数的依赖说明（DDL 已设计，尚待实测）

- `ticket_order`：见 §7.1；买家 ID、场次 ID、金额、状态、统一 15 分钟有效期、创建请求幂等键。
- `ticket_order_item`：见 §7.2；多区域与自主选座／自动配座的购买意图、数量、单价快照。业务必须保留一对多明细，不因取消物理 FK 而合并到订单表。
- `ticket`：见 §7.3；支付成功时生成、关联订单明细与实际座位。保留历史退款门票；使用只对 VALID 状态生效的唯一索引，不使用普通 `UNIQUE(seat_id)`。
- 账号场次有效购票数量：已在 §8 使用 `PRIMARY KEY (user_id, session_id)` + 带条件的原子 `UPDATE` 设计；支付／退款均必须在同一事务内同步计数。
- 订单幂等：需稳定的业务幂等键／合法状态条件更新（数据库唯一约束仍允许使用）。
- 免费预约：单账号、单场次同时只有一条有效预约；取消后应能再次预约且保留历史。需要设计仅约束**当前有效预约**的方式，不应盲目 `UNIQUE(user_id, session_id)`。

## 5. 数据巡检（移除外键后必须补上的保障）

### 5.1 检查不存在父活动的场次

```sql
SELECT s.activity_session_id, s.activity_id
FROM activity_session AS s
LEFT JOIN activity AS a ON a.activity_id = s.activity_id
WHERE a.activity_id IS NULL;
```

### 5.2 检查区域规划容量、实际座位和余量是否吻合

```sql
SELECT
    z.zone_id,
    z.total_amount,
    z.remains_amount,
    COUNT(s.seat_id) AS configured_seats,
    SUM(CASE WHEN s.allocation_status = 'AVAILABLE' THEN 1 ELSE 0 END)
        AS available_seats
FROM session_zone AS z
LEFT JOIN session_seat AS s ON s.zone_id = z.zone_id
GROUP BY z.zone_id, z.total_amount, z.remains_amount
HAVING z.total_amount <> configured_seats
    OR z.remains_amount <> available_seats;
```

草稿配置尚未完成时，规划容量与已录入座位数可能暂时不一致；应对正式发布的场次执行严格对账。还应继续补充：找不到父场次的 Zone、找不到父 Zone 的 Seat、门票与座位有效状态不一致等巡检。


### 5.3 核对账号场次有效购票计数（V2.2 新增）

```sql
SELECT
    p.user_id,
    p.session_id,
    p.valid_count,
    COUNT(t.ticket_id) AS actual_valid_tickets
FROM user_session_purchase AS p
LEFT JOIN ticket_order AS o
    ON o.buyer_id = p.user_id AND o.session_id = p.session_id
LEFT JOIN ticket_order_item AS i
    ON i.order_id = o.order_id
LEFT JOIN ticket AS t
    ON t.order_item_id = i.order_item_id AND t.status = 'VALID'
GROUP BY p.user_id, p.session_id, p.valid_count
HAVING p.valid_count <> COUNT(t.ticket_id);
```

正常支付与退款事务中必须同步计数。对账仅用于发现异常，不能未经审计就自动用 `COUNT(*)` 覆盖计数表；还需检查是否存在没有计数行的有效门票，以及引用了不存在用户／场次的计数行。

```sql
SELECT p.user_id, p.session_id
FROM user_session_purchase AS p
LEFT JOIN app_user AS u ON u.id = p.user_id
LEFT JOIN activity_session AS s ON s.activity_session_id = p.session_id
WHERE u.id IS NULL OR s.activity_session_id IS NULL;
```

## 7. 票务交易表（沿用 V2.1，经 V2.2 身份与模式补齐）

> 依照 requirements.md V1.1：一笔订单只针对一个活动场次，可以购买多张付费门票；不预占库存和座位；模拟支付成功后才生成门票。该“单场次单订单”是第一期的明确技术简化，订单内可包含该场次多个付费区域。
>
> 以下三表的 `buyer_id`、`session_id`、`order_id`、`zone_id`、`requested_seat_id`、`order_item_id` 和 `seat_id` 均为**逻辑关联**，不声明数据库 FOREIGN KEY。相关业务归属、权限和生命周期由 Service + 同库事务校验。

### 7.1 `ticket_order` — 票务订单主表

```sql
CREATE TABLE ticket_order (
    order_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    buyer_id BIGINT NOT NULL COMMENT 'Logical reference to app_user.id',
    session_id BIGINT NOT NULL COMMENT 'Logical reference to activity_session.activity_session_id',
    request_id VARCHAR(64) NOT NULL COMMENT 'Idempotency key for order creation requests by the same user',

    total_amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING_PAYMENT',

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3)
        GENERATED ALWAYS AS (DATE_ADD(created_at, INTERVAL 15 MINUTE)) STORED,
    paid_at DATETIME(3) NULL,
    closed_reason VARCHAR(100) NULL,

    CONSTRAINT chk_ticket_order_amount CHECK (total_amount > 0),
    CONSTRAINT chk_ticket_order_request_id
        CHECK (CHAR_LENGTH(TRIM(request_id)) > 0),
    CONSTRAINT chk_ticket_order_status
        CHECK (status IN (
            'PENDING_PAYMENT', 'PAID',
            'PARTIALLY_REFUNDED', 'REFUNDED', 'CLOSED'
        )),
    CONSTRAINT chk_ticket_order_status_fields
        CHECK (
            (status = 'PENDING_PAYMENT'
                AND paid_at IS NULL AND closed_reason IS NULL)
            OR
            (status = 'CLOSED'
                AND paid_at IS NULL AND closed_reason IS NOT NULL)
            OR
            (status IN ('PAID', 'PARTIALLY_REFUNDED', 'REFUNDED')
                AND paid_at IS NOT NULL AND closed_reason IS NULL)
        ),
    CONSTRAINT chk_ticket_order_paid_time
        CHECK (paid_at IS NULL
            OR (paid_at >= created_at AND paid_at < expires_at)),

    CONSTRAINT uq_ticket_order_request UNIQUE (buyer_id, request_id),
    INDEX idx_ticket_order_buyer_created (buyer_id, created_at),
    INDEX idx_ticket_order_session_status (session_id, status),
    INDEX idx_ticket_order_expiry (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**字段与约束理由：**

- `created_at` 由数据库自动填充；`expires_at` 是基于该创建时间加 **15 分钟**的存储生成列，避免应用层传入互相矛盾的过期时间，也不使用非确定性的 `NOW()` 放入 `CHECK`。如未来将 15 分钟改成可配置参数，应迁移为普通字段，并由下单事务统一赋值；本版按照已确认的固定时长设计。
- `request_id` 由调用方在同一次下单请求的重试中复用，`UNIQUE(buyer_id, request_id)` 防止重复创建。Service 已采用方案 A，按原订单的场次及全部明细购买意图比较载荷，相同则返回原单，不同则返回 `409 IDEMPOTENCY_CONFLICT`。不比较客户端价格或总额，不新增摘要列。唯一约束只阻止重复插入，既不证明载荷相同，也不代替支付幂等，见 [ADR-003](decisions.md#adr-003创建票单按原明细实现请求幂等)。
- `total_amount` 为订单价格快照，等于各订单明细 `quantity × unit_price` 的求和；数据库 `CHECK` 不能跨表求和，Service 在创建主表和明细时于一个事务中校验。
- `paid_at` 与状态一一对应：未支付和已关闭为 NULL，已支付及其退款状态必须非 NULL；`CLOSED` 必须写入原因。`CHECK` 不能限制状态转移次序，Service 应通过有条件状态更新保证合法转换。
- 逻辑关系：`buyer_id → app_user.id`，`session_id → activity_session.activity_session_id`。门票订单不绑定赠票名额，不允许把 `GIFT` 区域写入明细。

### 7.2 `ticket_order_item` — 票务订单明细／购买意图

```sql
CREATE TABLE ticket_order_item (
    order_item_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL COMMENT 'Logical reference to ticket_order.order_id',
    zone_id BIGINT NOT NULL COMMENT 'Logical reference to session_zone.zone_id',

    allocation_mode VARCHAR(20) NOT NULL,
    requested_seat_id BIGINT NULL COMMENT 'Specified for user-selected seating; NULL for automatic seat assignment',
    quantity INT NOT NULL,
    unit_price DECIMAL(10,2) NOT NULL COMMENT 'Snapshot of the per-ticket price for the zone, confirmed at order creation',
    accept_non_adjacent TINYINT NOT NULL DEFAULT 0,

    CONSTRAINT chk_ticket_item_quantity CHECK (quantity > 0),
    CONSTRAINT chk_ticket_item_unit_price CHECK (unit_price > 0),
    CONSTRAINT chk_ticket_item_mode
        CHECK (allocation_mode IN ('SELECT_SEAT', 'AUTO_ASSIGN')),
    CONSTRAINT chk_ticket_item_mode_fields
        CHECK (
            (allocation_mode = 'SELECT_SEAT'
                AND requested_seat_id IS NOT NULL AND quantity = 1
                AND accept_non_adjacent = 0)
            OR
            (allocation_mode = 'AUTO_ASSIGN'
                AND requested_seat_id IS NULL)
        ),
    CONSTRAINT chk_ticket_item_non_adjacent
        CHECK (accept_non_adjacent IN (0, 1)),

    CONSTRAINT uq_ticket_item_requested_seat
        UNIQUE (order_id, requested_seat_id),
    INDEX idx_ticket_item_zone (zone_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**设计解释：**

- `ticket_order` 是交易主记录；`ticket_order_item` 是一个订单的多个购买项目。取消物理外键不等于取消此一对多明细表，也不需要把 `zone_id_1, zone_id_2...` 塞进订单主表。
- 自主选座：每个指定座位对应一条明细，`quantity=1`，`requested_seat_id` 非空；此时只是**购买意图**，不能在创建订单时将座位标记为 `SOLD`。`UNIQUE(order_id, requested_seat_id)` 阻止同一订单重复申请同一座位；自动配座的 NULL 可以重复出现。
- 自动配座：按区域保存一条或多条 `quantity=N` 明细，`requested_seat_id=NULL`；`accept_non_adjacent` 保存用户是否接受非连座。在支付事务中确定具体座位。
- `allocation_mode` 是创建订单时的模式快照。Service 校验其与场次发布时选定的选座方式一致；**V2.2 已在 `activity_session.seat_mode` 补齐场次级配置**，并规定已发布场次不得随意更改；这份明细快照必须与场次配置一致。
- `unit_price` 是确定的十进制快照，**不是指向 `session_zone.price` 的外键**。创建订单时检查该价格与区域当时有效配置一致，后续退款可据此验证单张应退金额。
- 同一订单不同明细的 `zone_id` 必须全部属于订单 `session_id`，各明细引用的座位须属于对应区域；仅靠主键/单行 CHECK 无法完成该跨表校验，Service 必须进行检查。

### 7.3 `ticket` — 支付成功后签发的独立门票

```sql
CREATE TABLE ticket (
    ticket_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_item_id BIGINT NOT NULL COMMENT 'Logical reference to ticket_order_item.order_item_id',
    seat_id BIGINT NOT NULL COMMENT 'Logical reference to session_seat.seat_id',

    paid_price DECIMAL(10,2) NOT NULL COMMENT 'Snapshot of the actual transaction price for this ticket',
    status VARCHAR(20) NOT NULL DEFAULT 'VALID',
    issued_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    refunded_at DATETIME(3) NULL,

    active_seat_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN status = 'VALID' THEN seat_id ELSE NULL END
    ) VIRTUAL,

    CONSTRAINT chk_ticket_paid_price CHECK (paid_price > 0),
    CONSTRAINT chk_ticket_status
        CHECK (status IN ('VALID', 'REFUNDED')),
    CONSTRAINT chk_ticket_status_time
        CHECK (
            (status = 'VALID' AND refunded_at IS NULL)
            OR (status = 'REFUNDED' AND refunded_at IS NOT NULL)
        ),
    CONSTRAINT chk_ticket_refunded_time
        CHECK (refunded_at IS NULL OR refunded_at >= issued_at),

    CONSTRAINT uq_ticket_active_seat UNIQUE (active_seat_id),
    INDEX idx_ticket_item (order_item_id),
    INDEX idx_ticket_seat_history (seat_id, issued_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**设计解释：**

- `ticket` 仅在模拟支付成功时生成；有效门票来自订单明细，不必重复保存座位排号和座号（可通过 `seat_id → session_seat` 查询）。为支持门票级退款，保留每张票的成交价快照 `paid_price`，在无优惠券的一期中应等于明细单价；Service 负责保证。
- 不能用普通 `UNIQUE(seat_id)`：这样会禁止退款后同座再次售票。`active_seat_id` 是虚拟生成列：有效票取真实 `seat_id`，已退款票为 NULL。MySQL 的 UNIQUE 允许多个 NULL，因此这条唯一约束**只阻止同时出现多张有效门票占用同一座位**，并允许任意多张已退款历史票指向同一座位。InnoDB 支持给虚拟生成列建立唯一二级索引。
- 此唯一索引是对 `session_seat.allocation_status` 原子占座的一道额外数据库保护，不能取代座位 `AVAILABLE → SOLD`、区域库存扣减以及门票插入应处于同一事务的要求。
- 赠票座位由 `session_seat.allocation_status=GIFT_ALLOCATED` 管理，按 V1.1 当前业务设计**不创建关联账号的付费 Ticket**。赠票库存和发放操作历史仍需另行建模。

### 7.4 三表状态与数据流

```text
创建票务订单（同一事务）：
  ticket_order: 1 行 PENDING_PAYMENT / expires_at = created_at + 15 分钟
  ticket_order_item: 按购买明细写入 N 行
  ticket: 不写入；不扣减区域库存、不占用座位

模拟支付（同一事务，受并发控制）：
  锁定并验证订单仍待支付且尚未到期，且场次有效
  校验买家、场次、Zone、目标 Seat 的逻辑归属、白名单、限购
  原子扣减 PAID 区域库存；按统一顺序占用指定/自动分配的 Seat
  按每张实际座位写入 ticket，状态 VALID
  更新 ticket_order.status=PAID、paid_at=数据库当前时间
  任一步失败，支付事务全部回滚；库存不足时在安全终止失败事务后，按业务约定另行关闭待支付订单（状态条件更新）

退款（同一事务，示意）：
  验证票有效、退款金额/政策及申请是否可受理
  将对应 ticket.VALID → REFUNDED，并写入 refunded_at
  将对应 Seat.SOLD → AVAILABLE，恢复相应 Zone 库存
  依据有效票数量更新 ticket_order 的退款进度
  任一步失败回滚；重复退款不得重复释放座位和库存
```

### 7.5 无物理外键仍需保证的业务一致性

1. **逻辑引用存在性**：订单买家（`app_user.id`）和场次、明细订单和 PAID 区域、所选座位与区域、门票与订单明细及座位，全部由 Service 校验，必要时通过对账 SQL 发现孤儿记录。不能只在高并发之前查询一次就默认永远有效。
2. **买家限购**：V2.2 已增加 `user_session_purchase` 计数表（§8）；Service 必须原子更新计数并与库存、座位、票单提交在同一事务，只用 `SELECT COUNT(*)` 再判断是不安全的。
3. **历史保留**：不能物理删除已生效的订单明细和历史门票。允许退款后新订单对应新门票再次引用同一座位。
4. **数据库约束边界**：`CHECK` 只能约束当前行，可校验状态、时间、数量、模式等；不能通过 `CHECK` 确保 `total_amount = SUM(items)`、`seat.zone_id = item.zone_id` 或 `ticket_count = item.quantity`。
5. **价格快照的冗余**：订单总额、明细单价与逐票成交价有意冗余，以便查询和退款。三层数值的一致性由 Service 事务、只读历史价格和定期对账保证。
6. **索引取舍**：为常见的用户订单、超时任务、区域查找、订单明细以及活跃座位冲突建立索引；不要无条件为每个关联 ID 追加所有单列索引，写入高峰下每个索引也需要维护成本。
7. **阶段边界**：用户认证、创建订单幂等、完整票务支付、支付时原子限购更新及自动配座已实现。退款申请、退款明细、改期响应、通知与赠票发放留待后续实现。创建幂等由唯一键与完整购买意图比较共同保证，不能只依赖 `request_id`；支付幂等由订单状态、行锁与事务共同保证。

### 7.6 验收用例（建表后再用 MySQL 实测）

- 一笔订单有 2 条明细且涉及多个付费区域：能保存总金额及明细；0 张 ticket 直到支付成功。
- 自主选座明细 `quantity>1`：被 CHECK 拒绝；同订单重复指定相同座位：被 UNIQUE 拒绝。
- 自动配座明细未指定座位且数量为 2：允许；接受非连座必须保存选择。
- 模拟支付成功后，写入与购买数量相符的 VALID 门票，每张有独立 seat_id。
- 同一有效座位的第二张 VALID 票：`uq_ticket_active_seat` 拒绝。
- 原票退款后，该 seat_id 可以在新订单下签发另一张 VALID 票；历史票保持 REFUNDED。
- 订单创建两次且 `(buyer_id, request_id)` 相同：数据库拒绝重复插入。
- 过期订单、CLOSED 订单不可再次支付；退款只恢复成功退款数量。
- 用户跨场次、跨区域混写订单：Service 拒绝，不产生门票。

> **验证状态**：依据 MySQL 8.4 官方的 DDL / CHECK / 生成列规则进行静态设计；尚未连接到 MySQL 8.4 实例实际运行 DDL、事务与压测，不得据此宣称生产高并发性能已经验证。


## 8. 账号场次购票计数与并发限购（V2.2 新增）

### 8.1 `user_session_purchase` — 当前有效购票计数

```sql
CREATE TABLE user_session_purchase (
    user_id BIGINT NOT NULL COMMENT 'Logical reference to app_user.id',
    session_id BIGINT NOT NULL COMMENT 'Logical reference to activity_session.activity_session_id',
    valid_count INT NOT NULL DEFAULT 0 COMMENT 'Current number of valid paid tickets that have not been refunded',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (user_id, session_id),
    CONSTRAINT chk_user_session_purchase_count CHECK (valid_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**建模说明：**

- **一人一场一行**：`PRIMARY KEY(user_id, session_id)` 同时是唯一性约束与两列的联合索引，不必再创建同样的 `UNIQUE`。
- `valid_count` 表示该账号**当前有效**（未成功退款）的付费票数，不统计 `PENDING_PAYMENT`、`CLOSED`、已退款门票、赠票或其他场次。退款完成时必须按实际成功退款票数减少；正在退款但未成功的门票仍计入。
- `max_tickets_per_user` **不存入本表**，而在 `activity_session` 中统一配置。计数是用户实时状态，上限是场次规则，二者不能混为一谈。
- `user_id`、`session_id` 为**逻辑关联**，无物理外键；Service 必须校验用户、场次和活动的有效性。
- 计数行首次创建后即使变成 0 也建议保留，避免并发支付与退款之间反复删除／重建同一复合主键记录。

### 8.2 首次购买时安全初始化计数行

由于 `UPDATE` 无法更新不存在的记录，支付事务需先保证 `(user_id, session_id)` 的计数行存在。

```sql
-- MyBatis parameter example; executed in the same transaction as payment.
INSERT INTO user_session_purchase (user_id, session_id, valid_count)
VALUES (#{userId}, #{sessionId}, 0)
ON DUPLICATE KEY UPDATE valid_count = valid_count;
```

两个线程第一次为同一账号、同一场次下单并发支付时，复合主键保证最终只有一条计数记录。冲突更新可能等待其他事务释放行锁；上述语句为**幂等初始化**，不代表增加购票数量。

### 8.3 原子占用限购额度

场次每账号限购 `max_tickets_per_user = 4`；该账号当前 `valid_count = 2`，支付订单需要 `quantity = 2` 张：

```sql
UPDATE user_session_purchase
SET valid_count = valid_count + #{quantity}
WHERE user_id = #{userId}
  AND session_id = #{sessionId}
  AND #{quantity} > 0
  AND valid_count <= #{purchaseLimit} - #{quantity};
```

应用要求**影响行数必须等于 1**。影响 0 行表示当前数量不满足条件或计数行不存在，应中止支付。`purchaseLimit` 必须在同一支付事务中从可信的已发布场次配置读取，不能由前端任意提供；发布后该限购配置冻结。

在两个线程同时从 2 尝试加 2 的场景中，第一个提交后计数变为 4，第二个事务重新检查时更新 0 行；若第一个事务因座位不足**回滚**，则计数回到 2，另一个合法支付仍可能成功。MySQL 行锁与条件更新保证该计数不会因并发成功超限，但**不自动保证区域库存、座位、订单一致**，它们仍要使用同一事务。

### 8.4 退款时原子恢复额度

```sql
UPDATE user_session_purchase
SET valid_count = valid_count - #{refundQuantity}
WHERE user_id = #{userId}
  AND session_id = #{sessionId}
  AND #{refundQuantity} > 0
  AND valid_count >= #{refundQuantity};
```

必须先确保对应门票确实处于可退款的 `VALID` 状态，且该退款请求没有被其他线程成功处理过；在**同一事务中**更新门票状态、退款记录（后续实体）、座位占用状态、区域库存及本计数。重复退款不得重复减少计数或恢复库存。

### 8.5 支付事务与锁顺序（Service 实现约束）

```text
1. 在事务中按主键 FOR UPDATE 锁定订单，核对当前买家、订单状态与数据库有效期
2. 调用 QualificationService.checkAgain，取得账号、活动、场次及当前适用名单的共享锁
3. 复核活动与场次交易状态、明细及区域关系，资格共享锁保持到事务结束
4. 如不存在则幂等初始化 (user_id, session_id) 计数行
5. 按复合主键定位计数行并执行限购条件 UPDATE（必须影响 1 行）
6. 按 zone_id 升序锁齐本单全部 PAID Zone 并条件扣减，确定全部候选后按全单 seat_id 升序条件占用
7. 为成功占用的每个座位生成 VALID ticket，并把订单更新为 PAID
8. 全部成功 COMMIT；业务校验或写入失败必须抛出异常并 ROLLBACK
```

以上是完整支付的设计约束，当前逐步实现资源组件，尚未开放支付端点。订单锁先于资格共享锁，资格锁又先于限购、库存和座位写锁，并保持到业务事务结束，具体顺序见 [locking.md](locking.md#ticket-payment-resources)。所有支付、退款、改期及取消路径的锁顺序都须协调。Java `@Transactional` 不代表自动免除死锁，遇到 InnoDB 死锁或锁等待需对**整个事务**按幂等设计处理可控重试。

**状态并发提示：** 订单超时关闭与支付都要锁定／条件更新同一笔订单，并使用数据库时间检查 `expires_at`；订单无法同时成为 `CLOSED` 和 `PAID`。

### 8.6 选座模式与订单明细的联动验收

| 场次类型 | `seat_mode` | 下单记录 | 支付时必须检查 |
|---|---|---|---|
| 免费活动 | `NULL` | 不生成付费 `ticket_order` | 免费预约单独建模 |
| 付费／自主选座 | `SELECT_SEAT` | 每个目标座位一条 `ticket_order_item`；`requested_seat_id` 非空、`quantity = 1` | 指定座位仍可用，且属于该付费场次区域 |
| 付费／自动配座 | `AUTO_ASSIGN` | `requested_seat_id = NULL`，按区域记录 `quantity`；可记录是否接受非连座 | 可分配座位数量、连座策略与用户选择都满足条件 |

`ticket_order_item.allocation_mode` 是下单时的业务快照；不能由前端提供与发布后的 `activity_session.seat_mode` 相矛盾的模式。无物理外键时，Service 仍须验证 `zone_id → session_id`、`seat_id → zone_id`、订单 `session_id` 三者一致。

### 8.7 本阶段必测用例

1. 新用户首次支付时，两个请求同时初始化同一 `(user_id, session_id)`：最终只能有一条计数记录。
2. 已购 2 张、限购 4 张、两笔订单各买 2 张并发支付：最终累计有效票数不得超过 4；最多一笔支付成功。
3. 先占用计数、后占座失败：整体事务回滚，计数恢复原值，不残留部分扣减。
4. 成功退款 1 张后：计数减 1、该门票失效、对应座位与区域库存依规则恢复。
5. 同一退款请求同时到达两次：计数与库存最多恢复一次。
6. 已发布付费场次 `seat_mode` 为 `SELECT_SEAT`，却提交 `AUTO_ASSIGN` 明细：拒绝。
7. 免费场次在发布前带有付费选座模式或限购值、付费场次缺失这些配置：拒绝发布。
8. 用户被禁用、订单过期或场次取消时：不得成功支付，即便仍存在可用库存和限购额度。

## 9. 边界、测试与迁移

**重要边界：** 当前的 0 FK 版本是一种为了研究高并发读写及应用层一致性的实验性选择。它避免了 FK 检查，却没有解决热点行竞争、流量洪峰、热点库存排队、数据库连接数限制等所有问题，不应对吞吐量做未经压测的保证。

在实际部署、开发完整支付／退款链路前，至少完成以下测试（新增的账号限购、身份和选座规则见 §8.7）：

1. **业务一致性**：不存在逻辑孤儿、负库存、跨场次错售、重复分配座位。
2. **库存并发**：库存为 100 时模拟多客户端并发支付，成功售出不得超过 100 张，余量与有效占座数一致。
3. **重复请求**：同一订单重复提交支付只生效一次；同一退款重复执行只恢复一次库存。
4. **故障回滚**：在扣减区域库存后故意制造占座失败，确认全部库存变更回滚。
5. **锁竞争观测**：记录支付延迟、成功率、错误率、死锁和锁等待；用 `EXPLAIN` 检查关键查询与更新索引。

**迁移注意：** 以上 `CREATE TABLE` 面向新库建表；若之前已在现有数据库创建带外键的表，请先备份数据、执行 `SHOW CREATE TABLE` 确认约束名称，并在迁移脚本中显式 `ALTER TABLE ... DROP FOREIGN KEY ...`。不要直接重复运行 `CREATE TABLE`，也不要通过 `SET foreign_key_checks=0` 代替真正删除外键约束。

**下一步：** 免费预约 Schema 与名额事务契约已在 §11 冻结，创建与取消 API 已接入。退款明细、赠票库存操作、商品及售后表随对应阶段设计。

### 9.1 已有 V2.1 数据库的增量迁移示例

如果已经执行过 V2.1 的 `activity_session` DDL，**不能再次执行 `CREATE TABLE activity_session`**，而应先备份并检查线上数据，然后在维护窗口执行类似以下迁移：

```sql
ALTER TABLE activity_session
    ADD COLUMN max_tickets_per_user INT NULL
        COMMENT 'Per-account ticket purchase limit for paid sessions; NULL for free sessions',
    ADD COLUMN seat_mode VARCHAR(20) NULL
        COMMENT 'Seat selection mode for paid sessions; NULL for free sessions',
    ADD CONSTRAINT chk_session_purchase_limit
        CHECK (max_tickets_per_user IS NULL
               OR (max_tickets_per_user > 0 AND max_tickets_per_user <= capacity)),
    ADD CONSTRAINT chk_session_seat_mode
        CHECK (seat_mode IS NULL
               OR seat_mode IN ('SELECT_SEAT', 'AUTO_ASSIGN'));
```

另行执行本版 `app_user` 与 `user_session_purchase` 的新表 `CREATE TABLE`。如果系统已经有真实账号、订单与发布活动，必须先做 ID 对齐和数据补齐，避免出现账号不存在或已发布付费场次配置为空的情况。跨表的付费／免费模式校验始终由 Service 负责。数据库迁移执行前应核对目标实例是否已经存在上述列或约束，避免重复添加。

### 9.2 新库部署与静态验证状态

新库推荐顺序：`app_user` → `activity` → `activity_session` → `session_zone` → `session_seat` → `ticket_order` → `ticket_order_item` → `ticket` → `user_session_purchase` → `whitelist_config` → `whitelist_member`。由于**没有物理外键**，顺序不由数据库强制，但业务校验应按父实体先创建的原则执行。

本节的建表顺序不代表完整支付、退款并发或生产性能已经验证。白名单模型与验证范围见第 10 节。


---

## 10. 白名单配置与成员（V2.3 新增）

本阶段冻结活动与场次两种作用域。建表定义使用 [V3 白名单迁移](../src/main/resources/db/migration/V3__create_whitelist_tables.sql)，不修改 V1、V2 迁移。

### 10.1 `whitelist_config`

| 字段 | 类型 | 含义 |
|---|---|---|
| `config_id` | `BIGINT` | 自增主键 |
| `scope_type` | `VARCHAR(20)` | `ACTIVITY` 或 `SESSION`，非空 |
| `scope_id` | `BIGINT` | 对应活动或场次 ID，非空且大于 0 |
| `created_at` | `DATETIME(3)` | 数据库填充创建时间 |
| `updated_at` | `DATETIME(3)` | 数据库填充创建时间，并在配置行内容变化时更新 |

`UNIQUE(scope_type, scope_id)` 保证每个活动或场次最多有一份配置。相同数字的活动 ID 与场次 ID 属于不同作用域，可以各有配置。该唯一索引也支持按作用域查找配置。

配置记录存在表示该作用域已明确配置白名单。配置可以没有成员，不能根据 `whitelist_member` 的行数推断配置是否存在。

### 10.2 `whitelist_member`

| 字段 | 类型 | 含义 |
|---|---|---|
| `member_id` | `BIGINT` | 自增主键 |
| `config_id` | `BIGINT` | 逻辑关联 `whitelist_config.config_id`，非空且大于 0 |
| `user_id` | `BIGINT` | 逻辑关联 `app_user.id`，非空且大于 0 |
| `created_at` | `DATETIME(3)` | 数据库填充创建时间 |

`UNIQUE(config_id, user_id)` 阻止同一账号重复加入同一份配置，同时支持查询配置成员和判断指定账号是否在名单内。同一账号可以加入不同配置。

### 10.3 配置状态与资格语义

| 场次配置 | 活动配置 | 适用规则 |
|---|---|---|
| 不存在 | 不存在 | 满足其他业务条件的登录账号可参与 |
| 不存在 | 存在 | 继承活动名单，活动名单为空时无人通过 |
| 存在，成员为零 | 任意 | 场次显式空名单，无人通过，不回退到活动名单 |
| 存在，成员非空 | 任意 | 仅按场次名单判断，完全覆盖活动名单 |

清空成员保留配置记录，表示显式空名单。取消配置则在同一事务中删除该配置的成员与配置记录，使活动恢复公开规则，或使场次恢复继承规则。创建活动或场次时不自动创建空配置。

公开表示满足基础条件的登录账号通过名单校验，交易状态、时间、库存和限购仍由业务 Service 校验。`USER` 与 `PUBLISHER` 遵守相同名单规则，场次显式空名单同样拒绝活动所有者。

成员增删不修改配置行自身字段，因此不会自动改变 `whitelist_config.updated_at`。`PublisherWhitelistService` 在成员实际增删后显式更新该字段，不能仅依赖 `ON UPDATE CURRENT_TIMESTAMP(3)`。

### 10.4 应用边界与验证

数据库通过主键、唯一约束、非空约束和单行 `CHECK` 保护上述模型。`PublisherWhitelistService` 校验作用域对应的活动或场次存在、归属当前发布者及待加入的成员账号存在，并维护配置移除与成员增删的事务完整性。接口契约见 [白名单管理 API](whitelist.md)。

配置行不存在时无法锁定该行。名单管理先排他锁定所属 Activity，再按作用域锁定配置。资格检查通过 Activity 共享锁与名单管理协调，并使用锁定读查询当前账号、资源、配置和成员，操作顺序记录在 [locking.md](locking.md#qualification-reads)。完整支付、预约的事务锁顺序随对应功能扩充。名单管理允许活动为 `DRAFT` 或 `PUBLISHED`，`CANCELLED`、`FINISHED` 及其他状态返回 `409 WHITELIST_NOT_EDITABLE`。白名单变更不套用核心配置的 `DRAFT` 限制。

名单变更不撤销已成功预约或已支付门票，待支付订单在支付时重新校验资格。商品白名单与活动名单独立，本阶段不扩展 `PRODUCT` 作用域。

`QualificationService.check(activityId, sessionId)` 与 `checkAgain(activityId, sessionId)` 从可信身份上下文取得当前账号，要求已有业务事务。前者已接入 `TicketOrderService.createOrder`，后续预约也复用该入口，后者供支付复查使用。`canParticipate(userId, activityId, sessionId)` 保留为内部布尔查询。三个入口共用账号、活动与场次归属检查及名单判断，每次读取当前适用配置。场次配置存在时直接采用场次成员结果，只有场次配置不存在时才继承活动配置，两级均未配置时采用公开规则。事务与错误契约见 [当前架构](architecture.md)。

[白名单模型 MySQL 集成测试](../src/test/java/com/limitactivitysystem/whitelist/integration/WhitelistSchemaMySqlIntegrationTest.java) 从 V3 迁移读取 DDL，在同一连接的临时表上验证配置缺失、显式空名单、清空成员、作用域唯一性及成员唯一性。该模型测试不执行正式库迁移，管理接口、资格逻辑及并发验证见 [白名单管理与资格查询](whitelist.md)。

迁移中的建表语句数量可通过 PowerShell 核对：`(Get-ChildItem src/main/resources/db/migration -Filter 'V*__*.sql' | Select-String -Pattern '^CREATE TABLE ').Count`。

### 10.5 发布冻结与白名单例外

| 字段 | `PUBLISHED` 后的普通修改 |
|---|---|
| `activity.type`、`open_time`、`close_time` | 冻结 |
| `activity_session.capacity`、`max_tickets_per_user`、`seat_mode` | 冻结 |
| `session_zone.price`、`total_amount` | 冻结，当前票价位于 `session_zone.price` |
| `session_seat` 行列、座位编号、分配优先级及增删 | 冻结 |
| `activity_session.start_time`、`end_time` | 普通编辑冻结，专用改期流程留到 Day 22 |
| `whitelist_config`、`whitelist_member` | 所属发布者仍可维护，保留上述配置语义 |

这些跨表生命周期限制由 Service 维护，不由白名单表的单行 `CHECK` 推断。当前新增配置入口锁定 Activity 并检查 `DRAFT`，发布后返回 `409 ACTIVITY_NOT_DRAFT`。通用核心资源编辑入口尚未开放，普通编辑请求返回 `404` 或 `405`。

AT-04、AT-16 的当前测试范围及复跑命令见 [阶段验收](architecture.md#at-04-与-at-16-的阶段验收)。状态转换见 [state-machines.md](state-machines.md)。预约、票单、支付及改期的完整业务验收仍在对应阶段完成。

## 11. 免费预约与独立名额（V2.4 新增）

规范 DDL 为 [V4__create_free_reservation_tables.sql](../src/main/resources/db/migration/V4__create_free_reservation_tables.sql)，设计取舍与事务边界见[免费预约 Schema](free-reservations.md)。V1～V3 保持原迁移历史，已有库通过 V4 增加两张表。

`free_reservation` 保存 `reservation_id`、`user_id`、`session_id`、`status`、`created_at`、`cancelled_at`。状态仅 ACTIVE / CANCELLED；取消保留原行，重新预约新建另一行。`active_session_id` 为 VIRTUAL 生成列：ACTIVE 时为 session_id，其余合法状态为 NULL。`UNIQUE(user_id, active_session_id)` 仅限制有效预约，不限制同账号同场次的 CANCELLED 历史条数。

`free_session_quota` 以 session_id 为主键，保存已发布容量快照 capacity 和运行计数 remaining。首次预约事务按 FREE / PUBLISHED 场次惰性初始化，重复初始化不重置剩余量。数据库 CHECK 要求 capacity > 0、0 ≤ remaining ≤ capacity。扣减使用 `UPDATE ... SET remaining = remaining - 1 WHERE session_id = ? AND remaining > 0`，只有影响一行才可继续写预约，并与预约 INSERT 同事务提交。

取消先锁 quota，再条件取消原 ACTIVE 记录；只在状态更新影响一行时返还一份名额。完整契约包含用户归属、数据库时间和场次生命周期校验。FREE 预约不依赖 Zone、Seat、Ticket 或 user_session_purchase。

用户与场次存在性、FREE 归属、快照等于发布容量属于逻辑关联约束，由 FreeReservationService 与[对账 SQL](../tests/sql/reservation_invariants.sql)维护。创建与取消接口见[免费预约 API](api/reservations.md)。

## 12. 门票退款申请与逐票明细（V2.5 新增）

[V5__create_ticket_refund_tables.sql](../src/main/resources/db/migration/V5__create_ticket_refund_tables.sql) 建立 ticket_refund_request 与 ticket_refund_item。主申请保存买家、订单、请求键、原因、服务器申请时间、状态及政策时间快照；明细按 Ticket 保存实际成交价、手续费比例、应退金额、状态和失败原因。字段、金额类型和受理边界见 [退款申请契约](ticket-refunds.md)。

主表 UNIQUE(buyer_id, request_id) 保护申请幂等。明细 UNIQUE(refund_id, ticket_id) 拒绝申请内重复票，生成列 claimed_ticket_id 的唯一键保证同票在 PENDING、PROCESSING、SUCCEEDED 明细中只出现一次。FAILED 行的生成键为 NULL，保留失败历史并允许后续新申请。

主表不持久化 total_refund。退款汇总按 refund_id 对逐票明细的 refund_amount 求和，避免汇总列与明细金额不同步。

金额 CHECK 要求原价为正、费率在 [0,1]、退款金额在 [0,原价] 且等于逐票两位 ROUND 的计算结果；FAILED 明细必须有 failure_reason。Java 使用 BigDecimal 与 HALF_UP。外部关联由受理 Service 沿 Ticket → OrderItem → Order 验证，沿用零物理 FK。

## 13. 版本记录

- **V1**：四张基础表采用物理外键，训练基础关系模型与引用完整性。
- **V2.0**：移除物理外键，保留逻辑关联与数据库基础约束；新增座位占用状态，明确 Service 校验、条件更新和巡检职责。
- **V2.1**：增加 `ticket_order`、`ticket_order_item`、`ticket` 三张票务表，支持独立门票、订单明细及退款后座位重新销售。
- **V2.2**：新增 `app_user`、`user_session_purchase`；为 `activity_session` 增加 `max_tickets_per_user` 与 `seat_mode`，实现以复合主键和原子条件更新为核心的账号场次限购并发模型，并衔接两种座位分配模式。共 9 张已设计表，0 个物理 FOREIGN KEY。
- **V2.3**：新增 `whitelist_config`、`whitelist_member`，通过配置记录存在与否区分未配置与显式空名单。共 11 张已设计表，0 个物理 FOREIGN KEY。
- **V2.4**：新增 `free_reservation`、`free_session_quota`，采用生成有效键保留预约历史，通过独立名额条件更新保护剩余量。共 13 张已设计表，0 个物理 FOREIGN KEY。
- **V2.5（本版）**：新增 `ticket_refund_request`、`ticket_refund_item`，保存服务器申请时间、政策与逐票金额快照，保护申请幂等及同票有效退款唯一性。共 15 张已设计表，0 个物理 FOREIGN KEY。
