# 免费预约创建与取消 API

两个接口都要求 `Authorization: Bearer <JWT>`。USER 和 PUBLISHER 都可以为自己预约，账号须为 ACTIVE。请求无正文，也接受 `{}`；非空对象返回 `400 INVALID_RESERVATION_REQUEST`，用户、数量、状态和时间均由服务端确定。

## 创建预约

`POST /sessions/{sessionId}/reservations` 成功返回 `201 Created`：

```json
{
	"reservationId": 101,
	"userId": 7,
	"sessionId": 20,
	"status": "ACTIVE",
	"createdAt": "2026-10-28T19:58:00",
	"cancelledAt": null
}
```

创建要求场次存在、所属活动为 FREE / PUBLISHED、场次仍为 SCHEDULED，数据库当前时间位于活动 `[open_time, close_time)` 且早于场次开始。账号与当前适用白名单通过 `QualificationService.check` 检查。

`FreeReservationService.create` 使用独立的 `REQUIRES_NEW / READ_COMMITTED` 事务。账号、活动、场次与名单共享锁保持至事务结束；名额初始化、条件扣 1 和新 ACTIVE 记录 INSERT 同事务提交。初始化不重置已有剩余量，最终 INSERT 同时检查数据库窗口与生命周期，锁等待后和写入后重新取数据库时间复核。

预约成功即占一个名额，响应没有 orderId、expiresAt 或支付步骤。同账号可以预约同一活动的不同场次；同场次已有 ACTIVE 时返回 `409 RESERVATION_ALREADY_ACTIVE`，原记录和名额不变。

## 取消预约

`POST /reservations/{id}/cancel` 成功返回 `200 OK`，保持原预约 ID、用户、场次与创建时间，状态为 CANCELLED，并带数据库记录的 cancelledAt。

取消要求本人、ACTIVE、数据库当前时间严格早于 `session.start_time`。活动预约窗口已经关闭时，只要场次尚未开始，仍可取消；取消不重新检查白名单。账号状态、归属和时间检查仍然执行。

取消事务先读取预约确定作用域，按账号 → Activity → Session → quota → reservation 取得锁并重读当前记录。条件更新 ACTIVE → CANCELLED 成功一行后，才返还一份名额。返还影响零行或其他步骤失败时，预约状态与名额一起回滚。

重复取消返回 `409 RESERVATION_NOT_ACTIVE`，不再返还。取消后重新预约创建另一个 ID，保留旧 CANCELLED 行。重放旧 ID 的取消不会释放新预约的名额。

## 错误

| 场景 | HTTP / code |
|---|---|
| 缺少或无效凭据、认证时账号已禁用 | 401 UNAUTHORIZED |
| 认证后在业务事务中发现账号已禁用 | 403 USER_DISABLED |
| 非法 ID、非空或错误格式的正文 | 400 INVALID_RESERVATION_REQUEST |
| 场次或所属活动不存在 | 404 SESSION_NOT_FOUND / ACTIVITY_NOT_FOUND |
| 非免费活动 | 400 ACTIVITY_NOT_FREE |
| 活动未发布 | 409 ACTIVITY_NOT_PUBLISHED |
| 场次取消或结束 | 409 SESSION_NOT_AVAILABLE（创建） |
| 场次已开始，包括精确开始时刻 | 409 SESSION_STARTED |
| 当前名单不允许创建 | 403 QUALIFICATION_DENIED |
| 预约窗口未开或已关闭 | 409 RESERVATION_WINDOW_NOT_OPEN / RESERVATION_WINDOW_CLOSED |
| 已有有效预约 | 409 RESERVATION_ALREADY_ACTIVE |
| 没有剩余名额 | 409 RESERVATION_QUOTA_EXHAUSTED |
| 预约不存在或不属于当前用户 | 404 RESERVATION_NOT_FOUND / 403 RESERVATION_ACCESS_DENIED |
| 预约已取消 | 409 RESERVATION_NOT_ACTIVE |
| 场次配置异常或最终条件写入未生效 | 409 INVALID_SESSION_CONFIG / RESERVATION_CONFLICT |

技术失败继续传播，不伪装成名额不足。名额丢失、容量快照错误或无法返还属于数据一致性错误，事务回滚。

## 取消后的公共池竞争

A 取消并提交后，名额回到公共池。A 与 B 都需重新通过当前资格检查，再竞争同一 quota 行。原用户没有保留权，先成功完成名额事务的一方取得新预约。

真实 MySQL 测试分别让 A 与 B 先取得名额，确认两种结果都合法：旧预约保持 CANCELLED，新预约为另一个 ID，失败方不产生记录或额外扣减。实现与证据见[免费预约事务验证](../free-reservations.md#api-与事务验证)。

AT-01～AT-03、完整必测矩阵、对账 SQL 和交付清单见 [Day 9 免费预约验收](../day9-concurrency.md)。状态仅为 [ACTIVE → CANCELLED](../state-machines.md#免费预约状态机)。
