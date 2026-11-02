# Day 12 赠票故障与白名单变化回归

本次完成第 14、15 项验收。新增测试共十六个：两组指定赠票故障实验，以及十四个白名单变化场景。连同已有赠票分配测试，本轮 **88 项通过，失败 0、错误 0、跳过 0**，没有修改生产业务逻辑。

测试通过真实认证、发布者名单管理 API、订单、支付、退款、免费预约 API 和 MySQL 事务执行，不用外层测试事务包住业务操作。使用随机隔离库，完成后销毁该库。汇总见 [运行报告](verification/day12-whitelist-regression/run-summary.json)。

## 两组赠票故障实验

两组实验都请求分配五张，初始 GIFT Zone 的 total_amount、remains_amount 均为六，六个 Seat 均 AVAILABLE。

`specifiedGiftFailureExperimentsLeaveAllSeatsAvailableAndNoOperation` 在真实 SQL 成功后、抛异常前读取同一事务的状态，确认写入已经发生：

| 实验 | 注入位置 | 抛异常前已占用 Seat | 当时库存 | 当时主操作与明细 |
|---|---|---:|---:|---|
| A | 第二条 Seat 条件 UPDATE 成功后 | 2 | 6，尚未执行扣减 | 主操作 0，明细 0 |
| B | 座位全部占用、库存扣五、第一条明细 INSERT 成功后 | 5 | 1 | 未提交主操作 1，明细 1 |

HTTP 返回 `500 GIFT_TECHNICAL_FAILURE` 后，两组都检查八张资源与历史表的完整快照等于调用前，六个 Seat 均恢复 AVAILABLE，remains_amount 恢复六，total_amount 保持六，主操作和明细均不存在。没有在这两个实验中重试，终态就是完整回滚。

GIFT 对账和 PAID 资源对账均为零异常行。原始证据：[实验 A](verification/day12-whitelist-regression/day12-gift-specified-failure-A.json)、[实验 B](verification/day12-whitelist-regression/day12-gift-specified-failure-B.json)。其他故障点及原键重试仍由已有分配测试覆盖。

## 白名单变化 A–D

[WhitelistChangeMySqlIntegrationTest](../src/test/java/com/limitactivitysystem/activity/integration/WhitelistChangeMySqlIntegrationTest.java) 始终复用同一份买家 JWT，避免把重新登录误认为重新判断资格。所有名单修改都通过所属发布者的真实管理 API 提交。

活动级、场次级两种作用域分别运行。场次级场景还先配置空的活动级名单，再用场次名单允许买家，验证场次覆盖和取消场次配置后的继承语义。

| 场景 | 数量 | 实测结果 |
|---|---:|---|
| A：有资格时创建待支付单，移除成员后支付 | 2 | 返回 403 QUALIFICATION_DENIED。Order 仍 PENDING_PAYMENT，没有 Ticket，Seat 均 AVAILABLE，库存及限购未占用。 |
| B：支付成功后修改名单 | 4 | Ticket 仍 VALID，Seat 仍 SOLD，Order 仍 PAID。没有自动退款；重复支付返回原结果，资源快照不变。 |
| C：预约成功后修改名单 | 4 | 原预约仍 ACTIVE，cancelled_at 为空，名额继续由原预约占用，资源快照不变。 |
| D：移除成员后取消预约，再次预约 | 2 | 取消成功且保留 CANCELLED 历史、恢复名额；重约返回 403 QUALIFICATION_DENIED，不产生新 ACTIVE 记录。 |
| D：移除成员后退款，再创建新订单 | 2 | 原权益可正常申请并完成退款，Ticket REFUNDED、Seat AVAILABLE、Order REFUNDED、限购归零；新请求键创建订单返回 403 QUALIFICATION_DENIED。 |

B、C 的四个组合为两种作用域各测试“移除成员”和“取消名单配置”。两者不是同一个资格规则：移除成员保留名单配置，空名单仍拒绝未来参与；取消活动配置恢复公开规则，取消场次配置恢复活动级继承。本轮场次配置取消后继承的是已配置的空活动名单，但已有权益仍保持有效。

A、D 使用移除成员，保证未来资格确实被收回。D 的购票场景使用新的 requestId 验证新参与被拒绝；重放原 requestId 只返回原 REFUNDED 订单历史，不能恢复 Ticket 或产生新的参与资格。

名单修改前后比较十二张业务资源与历史表，排除名单表本身的预期修改。拒绝支付、重约或新建订单前后也比较完整资源快照。每个场景结束执行 refund_invariants.sql 与 reservation_invariants.sql，均要求零异常行。

## 证据与复跑

逐场景证据在 [验收目录](verification/day12-whitelist-regression)。文件名前缀对应 A、B、C、D，文件名包含 ACTIVITY 或 SESSION；B、C 另包含 MEMBER_REMOVED 或 CONFIG_REMOVED。

- [A：待支付订单重检](verification/day12-whitelist-regression/day12-whitelist-A-pending-SESSION.json)
- [B：已支付 Ticket 保持有效](verification/day12-whitelist-regression/day12-whitelist-B-paid-SESSION-MEMBER_REMOVED.json)
- [C：已成功预约保持有效](verification/day12-whitelist-regression/day12-whitelist-C-reservation-SESSION-CONFIG_REMOVED.json)
- [D：取消后重约](verification/day12-whitelist-regression/day12-whitelist-D-rebooking-SESSION.json)
- [D：退款后重新购票](verification/day12-whitelist-regression/day12-whitelist-D-repurchase-SESSION.json)

在项目根目录提供 DB_PASSWORD 后运行：

```powershell
	mvn '-Dtest=GiftAllocationMySqlIntegrationTest,WhitelistChangeMySqlIntegrationTest' test
```

本轮赠票分配测试共 74 项，白名单变化测试共 14 项。没有数据库凭据时跳过的 MySQL 测试不算验收通过。
