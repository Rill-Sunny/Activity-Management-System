# RabbitMQ、Transactional Outbox 与故障处理

## 消息链路

业务 Producer 在同一个 MySQL 事务中写业务数据和 `outbox_event`。Relay 之后将事件发布到 `las.events.exchange`，Direct Exchange 按 routing key 选择 Queue。Consumer 从 Queue 取消息，执行业务效果并确认处理。

| 组件 | 本项目中的职责 |
|---|---|
| Producer | 订单创建、支付成功、退款成功、场次改期；Ticket 退款事务还写 `TICKET_QUALIFICATION_CHANGED` |
| Exchange | `las.events.exchange` 按精确 routing key 分发；`las.events.dlx` 接收最终死信 |
| Routing Key | `order.expiry.delay`、`order.expired.execute`、`notification.payment.success`、`notification.refund.success` 等拓扑键 |
| Queue | 等待队列、执行队列、重试队列和 DLQ 均为持久队列；执行队列使用 quorum queue |
| Consumer | `AsyncEventConsumer` 处理订单过期、支付/退款通知、改期通知和到期保留；`TicketQualificationChangedConsumer` 登记资格联动任务并调用 Day 17 售后流程 |

订单创建只保存待支付订单、明细与过期事件，不扣库存、不锁座、不增加限购计数。库存占用仍发生在原支付事务中。

## Outbox 为什么必要

不能先提交订单、再直接发 RabbitMQ 消息。若应用在两步之间退出，订单已提交但消息不存在；若先发消息再回滚数据库，消费者会收到没有对应业务记录的事件。

订单、支付和退款 Service 使用 `OutboxEventWriter.append`，要求加入当前业务事务。数据库提交会同时持久化业务结果和对应事件；事务回滚时两者一起消失。数据库和 RabbitMQ 不提供一个跨资源原子提交，因此采用至少一次发布和消费幂等。

`outbox_event` 使用 `PENDING`、`CLAIMED`、`SENT`、`FAILED` 区分进度，并保留 `attempts`、`next_attempt_at`、`lease_until`、`lease_token`、`failure_reason`、`created_at`、`published_at` 与 `traceparent`。

Ticket 退款只在资格 Guard 内完成自己的业务变更。Ticket、座位/库存、退款结果、Guard version 与 `TICKET_QUALIFICATION_CHANGED` Outbox 在同一事务提交；退款事务不锁 `product_order`。事件仅描述资格相关变化，`eventVersion` 便于追踪，不把发生时的 `eligible=false` 当作后续消费时的事实。

## Relay 认领、确认与补发

Relay 在短 MySQL 事务内查询可发送或租约已过期的记录，使用 `FOR UPDATE SKIP LOCKED` 多 Worker 认领；它设置 `lease_until` 和随机 `lease_token` 后提交。网络发布发生在事务外，完成状态再用另一个短事务更新。token 是 fencing token，旧 Worker 不能覆盖接管后的状态。

事件发布为持久消息，并使用源 `event_id` 作为 Rabbit `messageId` 和 `x-event-id`。订单 TTL 由 `expires_at - 当前 MySQL 时间` 计算。达到最大 Relay 尝试次数后记录为 `FAILED`，错误原因可查询。超过租约且未耗尽次数的事件由其他 Worker 接管。

### Confirm、Return 与 ACK 的边界

| 机制 | 负责的范围 | 本项目的判断 |
|---|---|---|
| Publisher Confirm | Producer 到 RabbitMQ Broker：Broker 接受或拒绝发布 | 等待 correlated confirm；只有 ACK 才进入下一项检查 |
| Publisher Return | Exchange 到目标 Queue 的路由结果 | `mandatory=true` 且 returns 开启；Confirm ACK 但收到 Return 仍算失败，不能标记 `SENT` |
| Consumer ACK | Broker 到 Consumer：消费者表示该 delivery 已处理 | Listener 的事务 Service 正常返回、数据库提交完成后才 ACK |

Confirm ACK 与 Consumer ACK 不是同一确认。两者覆盖不同环节，也不能让数据库业务自动具备 exactly-once；Broker 已确认而 Relay 尚未更新 `SENT` 时，重启后会重发。数据库唯一约束与业务状态判断负责让重复消息安全。

## 消费幂等与提交顺序

Listener 解析原始 `eventId` 后，调用独立的 `AsyncEventHandlerService` 事务。处理进度 `(consumer_name,event_id)` 与通知或订单变更同事务提交。事务 Service 返回后 Listener 才调用 `basicAck`。若数据库提交后、ACK 前进程中断，RabbitMQ 会重投，`consumer_process` 唯一键阻止再次产生业务效果。

资格 Consumer 在独立事务写入 `consumer_process` 并登记任务，售后效果由另一事务提交。任务表按 `product_order_item_id` 唯一，补充消息级幂等，防止不同 event ID 对同一明细退款两次。消费时按 Ticket → TicketOrderItem → TicketOrder → Session 的关系，在 ACTIVITY 或 SESSION 范围重新统计当前有效票；旧事件不作为资格丧失的结论。若新票已经恢复资格则跳过，未发货明细复用 Day 17 模拟退款，已发货明细先进入 `WAITING_RETURN`。

订单关闭还有业务状态防线：只允许 `PENDING_PAYMENT` 且 `expires_at <= CURRENT_TIMESTAMP(3)` 的订单进入 `CLOSED/EXPIRED`。支付成功的订单不会因为迟到或重复的过期消息被关闭。通知使用 `(user_id,event_id)` 唯一键，并以消费者进度键防止重复创建。

## 有限重试、失败状态和 DLQ

消费者业务事务失败时，Listener 发布带递增 `x-las-retry-count` 的持久重试消息，并等待该重试消息的 Confirm；成功后 ACK 原 delivery。重试队列以固定 TTL 延迟，再经 DLX 返回执行路由。次数达到 `messaging.consumer.max-attempts`（默认 3）或重试消息无法确认时，原消息被 `reject(requeue=false)`，进入相应 DLQ，不会无限 requeue。

`consumer_failure` 保留消费者、event ID、尝试次数、最终状态和最近错误。DLQ 保存原消息，供排查与经授权的人工处置。订单超时、支付通知、退款通知和 Ticket 资格变化队列分别有对应重试与 DLQ 拓扑。售后任务自身的失败原因和 `next_attempt_at` 保存在任务表；售后失败不回滚已提交的 Ticket 退款。

`POST /admin/outbox-events/{eventId}/replay` 要求 `ROLE_ADMIN`。`ADMIN` 不可通过公开注册创建。重放只接受 Outbox 已失败或消费者失败且可恢复的事件，保留原 `eventId`；已经写入任意 `consumer_process` 的事件拒绝自动重放，避免重复副作用。

资格售后任务可通过 `GET /admin/ticket-qualification-after-sale-tasks/{taskId}` 查询。自动重试耗尽后，ADMIN 可用 `POST /admin/ticket-qualification-after-sale-tasks/{taskId}/replay` 将终态 FAILED 任务重置为 PENDING；任务重新执行时仍重查当前资格和明细状态。

## 订单 TTL 与数据库兜底

RabbitMQ TTL 是异步唤醒机制，不是业务时钟。Relay 使用数据库 `expires_at` 和 MySQL 当前时间计算剩余 TTL；消息到达后 Consumer 再从 MySQL 读取订单当前状态和到期时间，只有真实过期的待支付订单才关闭。

逐消息 TTL 可能因队头等待更长 TTL 的消息而延迟。Broker、Relay 或消费者不可用也可能让触发晚到。因此 Day 8 的数据库过期扫描继续运行，并独立调用相同的条件关闭逻辑。MySQL 条件更新负责保护并发支付与关闭。

## Trace 与本地服务

HTTP 请求的 `traceparent` 随 Outbox 持久化。Relay 从该上下文创建 producer span，Rabbit 消息传播 trace context，Consumer 记录业务 `eventId` 与 trace ID。资格联动日志含 `eventId`、`taskId`、`orderItemId`、失败原因及 trace ID。`qualification.event.received`、`qualification.event.skipped`、`qualification.aftersale.created`、`qualification.aftersale.succeeded`、`qualification.aftersale.failed` 与 `qualification.aftersale.retry` 指标不使用业务 ID 作为标签。Tempo 接收 OTLP trace；使用 trace ID 查询，并以 `eventId` 对应日志与数据库事件。

本地 Compose、管理端口和 Tempo 查询入口见 [RabbitMQ/Outbox 本地服务](rabbitmq-outbox.md)。故障注入场景、执行记录和对账 SQL 见 [Day 19 验收报告](../qa/reports/day19-mq-test.md) 与 [Day 20 资格联动验收](../qa/reports/day20-linkage-test.md)。集成测试使用真 MySQL 与 RabbitMQ，不使用 Mockito 替代确认、路由、TTL 或重投行为。

## Day 22 改期通知与到期保留

公告事务写入两条 Outbox，aggregate_id 均为独立的改期 event_id，payload 使用 rescheduleEventId 区分业务事件 ID 与 Outbox UUID。

| 事件 | 路由 | 消费者与事务结果 |
|---|---|---|
| SESSION_RESCHEDULED | session.reschedule.notice | session-reschedule-notification-v1；按快照中的不同买家写通知，与 consumer_process 一起提交后 ACK |
| RESCHEDULE_WINDOW_CLOSED | session.reschedule.wait → session.reschedule.retain | reschedule-retention-v1；按数据库截止时间处理最多 100 张票，条件写入 AUTO_RETAINED 与 consumer_process 一起提交 |

新队列为 las.session.reschedule.notice、las.session.reschedule.retain、las.session.reschedule.wait，notice 和 retain 各带 retry 与 dead 队列。Day 22 时拓扑共 20 个持久队列（Day 23 扩展为 23 个）；执行队列继续使用 quorum，重试继续使用有限次数和 Confirm。测试环境的统一 name-prefix 覆盖全部队列、交换机与绑定。

通知包含旧起止、新起止、特殊退款截止及事件查询入口。消费者从不可变事件和快照生成通知，不重新从当前 Ticket 集合推导历史受影响范围。

到期消息可能提前、延迟或重复。服务依据 MySQL Clock 和实际票、退款、响应状态决定是否保留，不改 Ticket 或 Seat。已退款或正在退款的票被排除，USER_CONFIRMED 不被覆盖。数据库扫描默认每 30 秒处理最多 100 张仍待处理的到期响应，逐票独立事务；未处理记录仍为 PENDING，后续扫描可恢复。

测试覆盖真实队列消费、重复窗口消息、数据库扫描、连接失败时 Outbox 保留与恢复补发。见 [Day 22 报告](../qa/reports/day22-reschedule-test.md)。本轮连接失败注入没有关闭共享 RabbitMQ 服务。

## Day 23 取消事件与持久任务

SESSION_CANCELLED 和 ACTIVITY_CANCELLED 共用 cancellation.execute 路由，aggregate_id 为 cancellation_event.event_id，payload 为 cancellationEventId。新增 las.cancellation.execute 及 .retry / .dead，当前完整拓扑为 23 个持久队列，命名空间、quorum、Confirm、有限重试规则沿用既有实现。

cancellation-generation-v1 在同一事务中写 consumer_process、生成至多 50 项任务并推进稳定主键游标；该事务返回后 ACK。其余分页和单项处理由 CancellationWorker 的持久扫描继续推进。MQ 是实际业务触发路径，数据库扫描同时提供无消息情况下的恢复保障。

每项认领与效果事务分离；失败以类型、itemId、SQLState/vendorCode（若为 SQL 异常）记录，不把可能包含凭据的原 SQL 异常消息直接暴露。自动尝试最多 5 次，失败延迟 10 秒，终态 FAILED 可由所属发布者明确重试。worker 中断可由 30 秒过期租约接管，成功后的重复消息或旧令牌无重复效果。

取消退款在同一效果事务继续写 REFUND_SUCCEEDED 和 TICKET_QUALIFICATION_CHANGED。后者走 Day 20 真实 RabbitMQ Consumer，逐个商品明细重新判断资格；普通商品、其他场次限定商品与仍有其他有效票的活动范围商品保留。已发货限定商品 WAITING_RETURN，完成既有模拟退回验收后才退款及条件回库。

取消批次 COMPLETED 的边界是票务、预约、赠票和资格事件持久化完成；独立商品售后继续通过 Day 20 任务接口查询、重试与收敛。外部队列停机实验依约留到 Day 28；Day 23 使用真实连接拒绝、消费者停止、重复投递与过期租约进行验证。
