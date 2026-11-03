# Product、SKU 与购买资格 API

本文记录商品目录、管理、展示及资格查询接口。商品订单预览和确认见[商品订单 API](product-orders.md)。支付库存竞争、售后及资格联动尚未实现。库存直接读取 MySQL，没有 Redis。

## 发布者管理

所有 `/publisher/products/**` 入口要求当前 ACTIVE PUBLISHER。Service 再从 CurrentUserProvider 取身份，共享锁复查账号、Activity 归属，并锁定 Product；知道 productId 不构成管理权限。请求中的额外字段均拒绝，包括值为 null 的 publisherId、status、createdAt、remainsStock 等。

| 方法 | 路径 | 结果 |
|---|---|---|
| POST | `/publisher/products` | 201，创建 DRAFT，归属发布者由认证及 Activity 关系决定。 |
| GET | `/publisher/products` | 200，仅当前发布者所属商品，含草稿与下架商品。 |
| GET | `/publisher/products/{productId}` | 200，本人商品及 SKU 列表。 |
| POST | `/publisher/products/{productId}/skus` | 201，仅 DRAFT 且从未上架时可创建。 |
| PUT | `/publisher/products/{productId}/skus/{skuId}` | 200，仅 DRAFT，完整提供 name、price、stock。 |
| GET | `/publisher/products/{productId}/skus` | 200，仅该 Product 的 SKU，无规格替换。 |
| POST | `/publisher/products/{productId}/publish` | 200，DRAFT/OFF_SALE → ON_SALE。至少一个 ACTIVE SKU，关联 Activity 必须 PUBLISHED；零库存 SKU 可上架展示。 |
| POST | `/publisher/products/{productId}/off-sale` | 200，ON_SALE → OFF_SALE，草稿不可下架。 |
| POST | `/publisher/products/{productId}/whitelist/members` | 201，正文仅目标 userId；只允许 PRODUCT_WHITELIST 商品。重复添加不重复建行。 |
| DELETE | `/publisher/products/{productId}/whitelist/members/{userId}` | 200，删除目标成员，缺失时幂等。 |
| GET | `/publisher/products/{productId}/whitelist/members` | 200，所属发布者读取目标账号 ID 列表。 |

上架、下架不接受非空正文，相同状态重试返回原结果。firstOnSaleAt 在首次上架生成，重新上架保留原值。当前没有 Product 规则编辑入口，也没有修改 ON_SALE 或 OFF_SALE SKU 价格/库存的接口。对商品首次销售后是否允许带版本修改核心规则的历史对话框问题，不以这个最小接口范围冒充用户已确认。

创建 Product：

```json
{
	"activityId": 100,
	"name": "2026音乐节纪念T恤",
	"description": "纪念款",
	"qualificationType": "PUBLIC",
	"purchaseLimit": 2,
	"refundPolicy": "STANDARD_7D"
}
```

activityId 为创建响应或已有合法资源的实际 ID。创建允许所属活动 DRAFT/PUBLISHED；不能向其他发布者的活动创建 Product。TICKET_REQUIRED 只能关联 PAID 活动，额外字段必须匹配以下组合：

| qualificationType | qualificationScope | qualificationSessionId |
|---|---|---|
| PUBLIC | 不传或 null | 不传或 null |
| PRODUCT_WHITELIST | 不传或 null | 不传或 null |
| TICKET_REQUIRED | ACTIVITY | 不传或 null |
| TICKET_REQUIRED | SESSION | 同 Activity 的实际正数场次 ID |

purchaseLimit 必须为正的 JSON 整数，统计维度是整个 Product，不能由 SKU 单独覆盖。refundPolicy 只取 STANDARD_7D、SPECIAL_NO_REASON_RETURN。响应展示服务端预设说明、版本和 requiresPolicyAcknowledgement；SPECIAL 说明保留质量问题及资格丧失等特殊原因边界，不代表所有售后都拒绝。购买确认及快照将在商品建单阶段接入，本轮没有提前声称可购买成交。

创建或修改 DRAFT SKU：

```json
{
	"name": "BLACK-M",
	"price": 99.00,
	"stock": 0
}
```

name 规范化去除首尾空白，同 Product 内区分大小写唯一。price 是正的 JSON 十进制数，最多八位整数、两位小数，不接收字符串或静默舍入超精度金额。stock 为 0～2147483647 的 JSON 整数，不接收小数、数字字符串或溢出值。服务端同时初始化 totalStock、remainsStock。

Product 创建不生成 SKU；客户端按每个规格分别创建。SKU 当前结构如下，ID 和时间来自服务器：

```json
{
	"skuId": 501,
	"productId": 1001,
	"name": "BLACK-M",
	"price": 99.00,
	"totalStock": 0,
	"remainsStock": 0,
	"status": "ACTIVE",
	"createdAt": "2026-11-02T12:00:00.000",
	"updatedAt": "2026-11-02T12:00:00.000"
}
```

同时存在 WHITE-L、remainsStock=20 时，读取 BLACK-M 仍返回零，不能借用或自动替换。当前库存配置只允许 DRAFT 阶段整体设置；未来真实库存的扣减与恢复使用专用条件更新，不复用这个编辑入口。

## 公开展示

以下 GET 允许匿名读取；提供了无效 Bearer JWT 时仍按统一认证规则拒绝。只展示 ON_SALE 商品及 ACTIVE SKU，关联活动须为 PUBLISHED 或 FINISHED。DRAFT、OFF_SALE、活动已取消的商品详情返回 404。

| 路径 | 响应 |
|---|---|
| `/products/{productId}` | Product、资格类型/范围、purchaseLimit、售后模板/说明和 skus 数组。 |
| `/activities/{activityId}/products` | 该 Activity 的公开商品数组，按 productId 升序。 |
| `/products/{productId}/skus/{skuId}` | 精确返回该 Product 的目标 SKU；属于别的 Product 时 404，不回退其他规格。 |

展示库存是当前 MySQL 读值，不是已保留的库存。资格通过也不承诺某个 SKU 有货。票务 open_time/close_time 不直接决定商品资格，合法旧 Ticket 不因票务停售失去资格。

## 查询当前账号资格

`GET /products/{productId}/qualification` 必须带当前账号 Bearer JWT，不属于匿名公开路径。禁止用 userId 参数查询另一账号。正常资格不足用 200 返回 allowed=false，技术异常不伪装成资格不足：

```json
{
	"userId": 12,
	"productId": 1001,
	"allowed": false,
	"reason": "TICKET_REQUIRED"
}
```

| 类型或状态 | 判定 |
|---|---|
| PUBLIC | 当前账号 ACTIVE、商品可公开交易即通过，不继承活动/场次名单。 |
| PRODUCT_WHITELIST | 必须存在独立 product_whitelist_member；空名单拒绝，活动名单成员不能替代。 |
| TICKET_REQUIRED / ACTIVITY | 买家在该 Activity 任一场次至少有一张真实 VALID Ticket。 |
| TICKET_REQUIRED / SESSION | 仅指定场次的真实 VALID Ticket 有效。 |
| 商品未上架或已不可见 | allowed=false，PRODUCT_NOT_ON_SALE。 |

ALLOWED 表示本次资格通过；名单不足为 PRODUCT_WHITELIST_REQUIRED，无合格票为 TICKET_REQUIRED。内部可信账号不存在或已停用时 USER_INACTIVE；HTTP 认证发现停用账号仍按统一规则返回 401。

持票 SQL 核对 Ticket → OrderItem → Order → Session，以及 Seat/Zone 的有效归属和 SOLD 状态，不只看订单 PAID。PARTIALLY_REFUNDED 且还剩适用 VALID Ticket 时允许；退掉最后一张后拒绝。其他账号、其他 Activity、错误场次、REFUNDED Ticket、FREE 预约和 GIFT 分配都不能提供资格。

内部 `ProductQualificationService.check(userId, productId)` 的 userId 必须来自可信调用者；独立调用建立 READ_COMMITTED 事务，加入业务事务时也要求 READ_COMMITTED，避免重用旧 RR 快照。对外始终调用 previewCurrent，从 CurrentUserProvider 决定账号。

当前是只读资格查询，不是未来商品支付授权令牌。商品订单、Product 限购计数、资格保护行及 Outbox 尚未接入；Day 16/20 必须在共同保护协议内重新检查资格，不能仅凭本次 allowed=true 成交。

## 错误、锁与验证

| 场景 | HTTP / code |
|---|---|
| 额外字段、非法数字、路径或枚举 | 400 INVALID_PRODUCT_REQUEST |
| 资格字段组合、场次归属、资格对应活动类型错误 | 400 PRODUCT_INVALID_CONFIGURATION |
| 其他发布者或事务内角色复查失败 | 403 PRODUCT_ACCESS_DENIED |
| Product / SKU 不存在或不公开 | 404 PRODUCT_NOT_FOUND / SKU_NOT_FOUND |
| 逻辑父资源损坏 | 409 PRODUCT_SCOPE_INVALID |
| 上架父活动不合法 | 409 PRODUCT_ACTIVITY_UNAVAILABLE |
| 上架缺少 ACTIVE SKU | 409 PRODUCT_NOT_READY |
| 上架后创建/修改 SKU | 409 PRODUCT_NOT_DRAFT |
| 同 Product 规格名重复 | 409 SKU_ALREADY_EXISTS |
| 活跃目标成员不存在 | 404 PRODUCT_MEMBER_NOT_FOUND |
| 数据库技术失败 | 500 或 503 PRODUCT_TECHNICAL_FAILURE |

管理路径 Account S → Activity S → 可选 Session S → Product X → SKU/Member；查询使用 Product S。名单变更的 Product X 与资格查询的 Product S 互斥，空名单也有稳定协调对象。SQL 成功后异常会回滚整个创建、SKU 写入或上架事务。真实事务和迁移证据见 [Day 14 验证](../day14-products.md)。
