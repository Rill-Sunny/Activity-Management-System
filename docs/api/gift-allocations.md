# GIFT 座位分配与历史 API

`POST /publisher/gift-zones/{zoneId}/allocations` 要求 ACTIVE 发布者身份及所属 Activity 的管理权限。zoneId 为正整数，服务沿 Zone → Session → Activity 读取并校验归属。

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/publisher/gift-zones/{zoneId}/allocations` | 分配指定数量的座位，保存操作及逐座历史。 |
| GET | `/publisher/gift-zones/{zoneId}/allocations` | 分页读取该区域的操作历史。 |
| GET | `/publisher/gift-allocations/{operationId}` | 读取一次操作及其实际 Seat 明细。 |

三个入口均要求当前 ACTIVE 发布者拥有所属 Activity。发布者不能仅凭 Zone 或操作 ID 查询他人的赠票资源。

## 请求与响应

正文只接受两个字段：

```json
{
	"requestId": "gift-op-001",
	"quantity": 5
}
```

requestId 为非空字符串，最多 64 字符，保留大小写及完整内容。quantity 必须是正的 JSON 整数，不接受小数、数字字符串、null 或溢出值。额外字段全部拒绝，包括值为 null 的字段。

publisherId 来自认证账号；sessionId、zoneType、库存及 Seat 状态来自数据库。不接受客户端指定 Seat、库存、规划容量、状态、时间或领取人身份。

新分配返回 `201 Created`，例如：

```json
{
	"operationId": 1001,
	"requestId": "gift-op-001",
	"publisherId": 7,
	"sessionId": 20,
	"zoneId": 101,
	"operationType": "ALLOCATE",
	"quantity": 5,
	"status": "SUCCEEDED",
	"createdAt": "2026-10-31T12:00:00.123",
	"seatIds": [10101, 10102, 10103, 10104, 10105]
}
```

Seat 按 allocation_priority、row_no、seat_no、seat_id 选择。响应中的 seatIds 按 ID 升序列出实际持久化的座位。操作、全部 Seat 明细、GIFT Seat 占用及库存扣减一起提交，任一步失败全部回滚。

## 生命周期与幂等

新分配要求 Activity 为 PAID、PUBLISHED，Session 为 SCHEDULED，且数据库当前时间严格早于 start_time。取得 Zone 锁后检查一次，全部资源和历史写入后再检查一次，锁等待或 SQL 等待跨过开始时间时完整回滚。

GIFT 是发布者资源管理，不使用买家的白名单、购票额度或售票开放窗口。只要上述生命周期允许，可以在公开开售前或停售后分配。没有领取人账号校验，不生成普通 Ticket。

`UNIQUE(publisher_id, request_id)` 保护操作幂等。同一发布者、同一 requestId、相同 Zone 和数量返回 `200 OK` 及原始完整结果，操作 ID、时间及 Seat 明细保持不变，不再占座或扣库存。当前库存不足、场次开始或结束不使历史重放变成新的分配，重放仍检查当前账号及资源归属权限。

同键改变 Zone 或 quantity 返回 `409 GIFT_IDEMPOTENCY_CONFLICT`。不同发布者可在自己的区域复用相同键。跨 Zone 并发竞争相同键时，失败方先完整回滚，再在新事务读取胜出记录并比较内容，不在失败事务中继续执行 SQL。

## 查询操作历史

`GET /publisher/gift-zones/{zoneId}/allocations?limit=20&afterOperationId=0` 返回 `200 OK`，参数如下：

| 参数 | 默认值 | 约束 |
|---|---|---|
| limit | 20 | 整数，1～100。 |
| afterOperationId | 0 | 非负整数，只返回 ID 大于该值的操作。 |

响应为 `{"allocations": [...], "nextAfterOperationId": 1001}`。allocations 中每项与 POST 的完整响应结构相同，按 operationId 升序排列；有下一页时，nextAfterOperationId 为本页最后一项的 ID。下一次将它作为 afterOperationId。没有更多记录时该值为 null；没有记录时返回 `{"allocations": [], "nextAfterOperationId": null}`。每次请求读取当时已提交的历史，不提供跨多页请求的固定快照。

`GET /publisher/gift-allocations/{operationId}` 返回 `200 OK` 及与 POST 相同的完整操作结构，operationId 必须为正整数。不存在的操作返回 `404 GIFT_OPERATION_NOT_FOUND`。

查询沿数据库关系复查当前账号及 Activity 归属，并用共享锁与分配事务协调；不更新库存、Seat 或操作。历史不要求场次仍未开始，活动或场次结束后仍可查询。若资源管理归属发生合法变更，访问权随当前 Activity 所属发布者判断，响应中的 publisherId 保留当时的操作人。

数量、时间和 seatIds 来自 operation / operation_item。即使当前 Seat 状态损坏，查询仍返回原操作记录，容量和历史巡检负责暴露不一致。若操作明细数不等于 quantity，查询返回 `409 GIFT_RESOURCE_CONFLICT`，不返回残缺的成功记录。响应不包含领取人账号、实名或核销字段。

## 错误

| 情况 | HTTP / code |
|---|---|
| 未登录、无效 JWT、认证时账号已禁用 | 401 UNAUTHORIZED |
| HTTP 角色不是 PUBLISHER | 403 FORBIDDEN |
| 参数、路径、额外字段或数量类型非法 | 400 INVALID_GIFT_REQUEST |
| Zone 不存在 | 404 GIFT_ZONE_NOT_FOUND |
| 操作不存在 | 404 GIFT_OPERATION_NOT_FOUND |
| 事务内发现账号不是发布者，或不拥有资源 | 403 GIFT_ACCESS_DENIED |
| 事务内发现账号已禁用 | 403 GIFT_USER_DISABLED |
| Session、Activity 缺失或锁后关联变化 | 409 GIFT_SCOPE_INVALID |
| 当前发布者对 PAID Zone 发起赠票分配或历史查询 | 409 GIFT_ZONE_NOT_GIFT |
| 生命周期不允许新分配 | 409 GIFT_SCOPE_UNAVAILABLE |
| 当前 GIFT Zone 库存不足 | 409 GIFT_INSUFFICIENT_INVENTORY |
| 库存与可用座位不一致、条件写入失败或历史明细缺失 | 409 GIFT_RESOURCE_CONFLICT |
| 相同业务键对应不同分配内容 | 409 GIFT_IDEMPOTENCY_CONFLICT |
| 数据库连接或锁等待等技术失败 | 503 GIFT_TECHNICAL_FAILURE |
| 其他数据库技术异常 | 500 GIFT_TECHNICAL_FAILURE |

技术异常保留为技术错误，不伪装为库存不足。失败后可复用原 requestId 重试；当前没有单独提交的 FAILED 历史。

本切片只提供 ALLOCATE。total_amount 是规划容量，分配只改变 remains_amount 和 Seat 状态。RELEASE 未开放；后续释放必须引用原操作及具体座位，不能按任意数量随机释放。模型、锁顺序与验收见 [GIFT 资源操作](../gift-inventory.md)。
