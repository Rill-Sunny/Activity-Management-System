# 场次与活动取消 API

取消立即生效，后续任务异步完成。所有管理接口要求有效发布者账号，且对象归当前账号管理；操作人由 JWT 确定，客户端不能提交 publisherId。

## 提交取消

`POST /publisher/sessions/{sessionId}/cancel` 或 `POST /publisher/activities/{activityId}/cancel`

```json
{"requestId":"cancel-session-1001","reason":"场馆无法使用"}
```

成功返回 202 和事件：eventId、cancelType、targetId、activityId、publisherId、operatorId、reason、cancelledAt、requestId。requestId 最长 64 字符，reason 最长 500 字符，均不能空白，未知字段返回 400。时间为项目数据库时区 Asia/Shanghai 的毫秒本地时间。

允许取消 PUBLISHED 活动内尚未结束的 SCHEDULED 场次，包含进行中的场次。活动整体取消只覆盖这些场次；已结束、FINISHED 和此前取消场次保持历史。当前模型的 FREE/PAID 由活动确定，不能在同一活动混合两类场次。

同一发布者相同 requestId、范围和原因返回原事件。重复键内容不一致或已取消对象的新请求返回 409，不创建第二个批次。活动随后整体取消不会重复接管此前已取消场次。

取消事务提交后，新票单、待支付付款、免费预约、赠票分配和改期拒绝；取消场次的白名单启用、增删成员、禁用也拒绝，其他正常场次仍可维护。退款、资源恢复和已有售后继续执行。交易入口直接读取并锁定生命周期，不使用缓存判断能否成交。

## 查询与恢复

`GET /publisher/cancellations/{eventId}?afterItemId=0` 返回事件、不可变场次范围、批次和最多 100 项明细。使用最后一项 itemId 翻页。批次记录 totalItems、succeededItems、failedItems、scanPhase 和 nextCursor；明细包含状态、累计 attemptCount、当前重试轮 retryCount、nextAttemptAt、租约、失败原因、processedAt、refundId。

批次 PENDING → PROCESSING → COMPLETED；存在达到重试上限的项时为 PARTIAL_FAILED。COMPLETED 表示票单、票务退款、预约和赠票全部处理完毕，资格变化事件已随退款持久化。限定商品是独立售后域，已发货商品仍可处于 WAITING_RETURN；不能把该批次完成解释为所有实物已经退回。

`POST /publisher/cancellations/{eventId}/items/{itemId}/retry` 无请求体，返回 202。仅将 FAILED/RETRY_PENDING 项重新放入待处理队列，重置本轮重试次数，保留累计次数和上次失败原因。成功、正在处理、已经待处理的项目不重复执行。其他发布者或跨批次项目不能重试。

每次扫描最多分页生成 50 个批次的一页、每页 50 项，并最多执行 50 项。单项自动尝试最多 5 次，失败等待 10 秒，认领租约 30 秒。后台每 10 秒扫描（初始 30 秒），可通过 cancellation.worker.enabled、delay、initial-delay 配置。消息重复、租约到期、效果提交后进程中断，均由唯一键、认领令牌、事务和数据库扫描恢复。

商品售后沿用 Day 20 的任务查询、重试接口及管理端 Outbox 重放接口。重放消息不重置已成功的 consumer_process，不产生重复退款。

## 公开取消信息与参与效力

- `GET /sessions/{sessionId}/cancellation`：场次取消原因、事件、生效时间和批次状态；未取消返回空对象。
- `GET /activities/{activityId}/cancellation`：活动整体取消信息；未整体取消返回空对象。
- `GET /activities/{activityId}`：已取消活动仍可返回 CANCELLED 状态；活动列表保持只列已发布活动。
- `GET /reservations/{reservationId}/participation`：当前预约所有人的 effective 布尔值。
- `GET /publisher/gift-items/{operationItemId}/participation`：所属发布者查询赠票参与效力。

参与效力直接检查预约/赠票记录和最新 Activity、Session 生命周期；取消后即使后台尚未处理也返回 false。以上参与判定接口不会改动资源。

错误使用 ProblemDetail 和 code。400 为请求错误；401 未登录；403 无权限；404 对象不存在；409 生命周期、幂等或并发冲突；503 数据库操作失败。503 后可使用原 requestId 重试，不把失败响应当作取消已提交的证明，应查询原请求结果。
