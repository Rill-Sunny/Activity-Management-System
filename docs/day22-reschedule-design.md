# F11 场次改期契约

本契约在 V17 迁移之前冻结。Day 22 实现改期，不实现 Day 23 取消批处理。

## 事件与快照

每次改期插入独立 `session_reschedule_event`，保存发布者、请求键、原因、新旧时间、公告与截止时间。发布者请求键以场次为范围幂等；同键不同内容返回冲突。只接受当前发布者拥有的 PUBLISHED 活动、尚未开始的 SCHEDULED 场次。

`reschedule_affected_ticket` 在公告事务内从 VALID Ticket 建立不可变快照，保存买家、订单、场次、座位和成交金额。`reschedule_response` 按事件与门票保存 PENDING、USER_CONFIRMED 或 AUTO_RETAINED。主动响应时间与系统保留时间分别存储，退款不覆盖响应历史。

特殊退款允许一次请求包含同一事件、同一买家的多个订单。`reschedule_refund_submission` 保存整体幂等内容，`reschedule_refund_link` 关联按订单生成的原有退款申请。整批受理原子提交，执行沿用原逐订单退款事务。所有表均无物理外键。

## 时间与政策

请求必须携带 UTC 偏移，服务器转换到现有数据库业务时区 Asia/Shanghai，精度为毫秒。公告与受理时间由数据库 Clock 提供，受理时间在取得生命周期锁与门票锁后确定。生产时钟不接受请求覆盖。

deadline = min(announcement + 7×24h, 此事件的新开始时间)，受理区间为 [announcement, deadline)。同时禁止在当前场次已经开始后发起新响应或新退款。既有合法退款申请的执行不重新校验窗口。普通退款独立适用自己的政策。

新开始必须早于结束、晚于当前数据库时间，且不早于 Activity.close_time（现有 ACT 统一窗口规则）。活动开售时间、票价、容量、门票与座位分配不变。多次改期各自保存原窗口和响应，只在本次公告时重新快照有效票。

## 锁与提交

公告取得 Account 共享锁 → Activity 共享锁 → Session 排他锁。支付和退款已有 Session 共享锁，因此 Session 排他锁是快照截止点。公告在 READ COMMITTED 下用非锁定读建立快照，不取得订单锁或逐票写锁，避免与 Order 优先协议形成环。

特殊退款按 Order ID 升序锁齐全部订单，然后 Account 共享锁 → Activity 共享锁 → Session 共享锁 → Ticket ID 升序。校验归属、快照、状态和窗口后，调用已有退款受理持久化逻辑。全域 `uq_refund_ticket_claim` 防止普通退款、不同改期事件重复占用同票。

主动确认与自动保留取得 Activity/Session 共享锁 → Ticket 共享锁 → Response 写锁。自动保留只处理到期 PENDING、当前 VALID 且没有有效退款申请的票；不更新 Ticket 或 Seat。每批扫描有上限，每张票独立事务。

公告事务一起保存事件、快照、响应初始行、场次新时间、缓存失效任务和两个 Outbox 事件。通知经真实 RabbitMQ 消费，通知及 consumer_process 一起提交后 ACK。窗口到期消息只是唤醒，扫描器与数据库时钟兜底；重复消息不重复变更。

## API

- `POST /publisher/sessions/{sessionId}/reschedules`：requestId、newStartTime、newEndTime、reason。
- `GET /publisher/sessions/{sessionId}/reschedules`：发布者查询完整历史。
- `GET /reschedules`：当前用户受影响事件列表。
- `GET /reschedules/{eventId}`：本次历史、当前用户快照与响应、特殊退款结果。
- `POST /reschedules/{eventId}/confirm`：ticketIds；重复确认返回原响应，不改首次响应时间。
- `POST /reschedules/{eventId}/special-refunds`：requestId、ticketIds；返回本次受理的原有退款申请列表，沿用 `/ticket-refunds/{refundId}/process` 执行。

未经授权不能查询他人快照或操作他人门票。消息、扫描和人工重试只恢复已持久化业务，不从客户端重建资格。
