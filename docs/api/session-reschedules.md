# 场次改期 API

所有接口需要 Bearer JWT。`/publisher/**` 还需要当前数据库账号具有 PUBLISHER 角色，并拥有该场次所属活动。时间请求必须包含 UTC 偏移，存储及规则计算使用 Asia/Shanghai，精度为毫秒。

## 发布改期

`POST /publisher/sessions/{sessionId}/reschedules`，成功返回 201 和事件历史。请求键在当前场次内唯一，相同内容重放返回原事件；同键不同内容返回 409。

```json
{
	"requestId": "reschedule-20261111-001",
	"newStartTime": "2026-12-18T19:30:00+08:00",
	"newEndTime": "2026-12-18T22:00:00+08:00",
	"reason": "场馆档期调整"
}
```

新开始必须晚于数据库当前时间、不早于活动 closeTime，且早于新结束。活动必须 PUBLISHED，场次必须 SCHEDULED 且当前尚未开始。额外字段被拒绝，operatorId 由服务端认证身份确定。

事件响应包含 eventId、sessionId、oldStartTime、oldEndTime、newStartTime、newEndTime、reason、operatorId、announcementTime、specialRefundDeadline。日期输出带 UTC 偏移。相同时间的无效改期返回 409 RESCHEDULE_UNCHANGED。

## 查询历史

- `GET /publisher/sessions/{sessionId}/reschedules?beforeId=...`：所属发布者查询该场次事件，按 eventId 倒序，每页最多 100 条。下一页使用本页最小 eventId。
- `GET /reschedules?beforeId=...`：当前用户被快照包含的事件，同样按 eventId 游标分页。
- `GET /reschedules/{eventId}`：本次事件、当前用户的票与响应、特殊退款处理结果。没有本次受影响票的账号返回 403。

Ticket 响应分别提供 status（选择状态）、ticketStatus（实际票状态）、respondedAt、autoRetainedAt、source。未发生的时间为空或不返回。退款记录独立展示 refundPolicy、sourceEventId、acceptedAt、status 和 result。

## 确认保留

`POST /reschedules/{eventId}/confirm`，成功返回 200 和当前用户响应。

```json
{
	"ticketIds": [1001, 1002]
}
```

每次选择 1～100 张不同的、本用户受影响票。新确认必须在本次窗口内且当前场次未开始，门票有效并没有已受理退款。重复确认保留首次 respondedAt；已经保存的 USER_CONFIRMED 可以幂等读取，但不重新授予退款资格。

确认后仍能在合法窗口内申请特殊退款。AUTO_RETAINED 与 USER_CONFIRMED 不互相覆盖。

## 特殊全额退款

`POST /reschedules/{eventId}/special-refunds`，成功返回 201。

```json
{
	"requestId": "reschedule-refund-001",
	"ticketIds": [1001, 1002]
}
```

同一事件、同一用户的选票可以来自多个订单。服务端整批受理，返回 submissionId、eventId、acceptedAt、refundPolicy 以及 refunds 数组。每个数组元素是原有逐订单退款申请，沿用 `POST /ticket-refunds/{refundId}/process` 执行模拟退款。

受理区间为 `[announcementTime, min(announcementTime+168h, newStartTime))`，当前场次已开始也拒绝新申请。原成交价全退，feeRate 为 0。执行时使用已受理报价，不因执行延迟越过截止而取消资格。

requestId 在当前用户的特殊退款提交中唯一。相同事件和相同排序票集重放原结果，同键不同内容返回 409。普通退款、其他改期事件和本事件退款共用同票唯一占用约束；存在已受理或成功退款时返回冲突。

## 错误与恢复

| HTTP | 典型 code | 含义 |
|---|---|---|
| 400 | INVALID_RESCHEDULE_REQUEST、INVALID_RESCHEDULE_TIME | 字段、偏移时间、时间顺序或统一窗口不合法 |
| 403 | RESCHEDULE_ACCESS_DENIED | 发布者、买家或快照归属不符 |
| 404 | RESCHEDULE_NOT_FOUND | 事件或场次不存在 |
| 409 | RESCHEDULE_WINDOW_CLOSED、RESCHEDULE_SESSION_UNAVAILABLE | 截止或生命周期不允许新操作 |
| 409 | RESCHEDULE_IDEMPOTENCY_CONFLICT、REFUND_ALREADY_REQUESTED | 请求键冲突或同票已有退款 |
| 503 | RESCHEDULE_DATABASE_FAILURE | 数据库操作失败，事务回滚，保留原 requestId 重试 |

通知失败不会回滚已提交改期。Outbox、consumer_failure 与 DLQ 沿用现有恢复机制。到期扫描默认每 30 秒处理最多 100 张票，PENDING 响应是扫描恢复依据。
