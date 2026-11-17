# Day 12 完成验收

第 16～22 项已补齐：赠票分配、历史列表与详情三个 API，PAID/GIFT 双向隔离，三类容量巡检，以及权限、幂等、最后一席并发和历史完整性测试。GIFT 操作保持 ALLOCATE-only；每份对应真实 Seat，不创建领取人账号或普通 Ticket。

## API 与历史契约

| 方法 | 路径 | 行为 |
|---|---|---|
| POST | `/publisher/gift-zones/{zoneId}/allocations` | 只接受 requestId、quantity，原子占座、扣库存并保存操作与逐座明细。 |
| GET | `/publisher/gift-zones/{zoneId}/allocations` | 操作 ID 升序游标分页，默认 20、最多 100 项。 |
| GET | `/publisher/gift-allocations/{operationId}` | 返回原操作人、时间、数量及确切 Seat 明细。 |

三个入口都核对当前 ACTIVE 发布者及 Zone → Session → Activity 的数据库归属。列表与详情只读历史，不要求场次仍未开始。publisherId 是当时的操作人；没有赠票领取、转赠、领取人查询或核销 API。参数、返回结构及错误见 [API 契约](../../docs/api/gift-allocations.md)。

操作历史来自 gift_inventory_operation 和 gift_inventory_operation_item。将某个已分配 Seat 的当前状态故意改坏后，列表与详情仍返回原 seatIds，巡检同时检出资源不一致。删除一条操作明细后，两个查询都返回 409 GIFT_RESOURCE_CONFLICT，避免以残缺历史表示成功。

查询与分配共用账号、Activity、Session、Zone 的父资源顺序。查询用 Zone 共享锁，分配用排他锁，Seat 写锁固定升序。真实 MySQL 实验观察到查询持锁时分配在 session_zone 等待，查询结束后分配成功。Payment、Refund、GIFT 完整路径见 [locking.md](../../docs/locking.md)。

## AT-06 与容量巡检

AT-06 覆盖三个边界：

1. SELECT_SEAT 不能指定 GIFT Zone 或冒用 PAID Zone 指定 GIFT Seat；支付再次拒绝被损坏为 GIFT 的区域。
2. AUTO_ASSIGN 只使用相应 PAID Zone；与 GIFT 分配并发后，两边座位、库存各自正确。赠票库存不足不会从 PAID 或其他 GIFT Zone 补座位。
3. Schema 的精确列清单与接口字段检查确认没有领取人绑定。分配不增加 app_user、ticket 或 user_session_purchase；操作人的 publisherId 仍保留。

[gift_invariants.sql](../../tests/sql/gift_invariants.sql) 包含三类容量巡检：

| 分类 | 不变量 |
|---|---|
| PAID | total_amount = remains_amount + SOLD Seat 数。 |
| GIFT | total_amount = remains_amount + GIFT_ALLOCATED Seat 数。 |
| Session | Σ PAID total_amount + Σ GIFT total_amount ≤ capacity。 |

三类异常分别造错并要求检出。Session 用例先分配五席，再将场次容量设为九，规划总量仍为十，必须报告 10 > 9；不能把运行库存下降当作规划容量下降。规划总量十、场次容量十一是合法状态。分配从不修改 total_amount。

同脚本还检查串池、历史数量、逻辑关联、当前已分配 Seat 的历史以及 GIFT Seat 没有普通 Ticket。等待并发线程结束后运行，脚本只读、不修数据。每个 GIFT 业务测试结束均对账：正常夹具必须零异常行，故意损坏夹具必须检出问题；正常 PAID 资源还执行退款对账。

## 自动化验收矩阵

以下分配测试位于 [GiftAllocationMySqlIntegrationTest](../../src/test/java/com/limitactivitysystem/activity/integration/GiftAllocationMySqlIntegrationTest.java)，白名单测试位于 [WhitelistChangeMySqlIntegrationTest](../../src/test/java/com/limitactivitysystem/activity/integration/WhitelistChangeMySqlIntegrationTest.java)。均经过真实 Security、HTTP、Service、MyBatis 和 MySQL，没有外层测试事务遮蔽业务提交。

| 场景 | 实测结果及测试方法 |
|---|---|
| USER 分配、发布者 A 操作 B 的区域 | 403，资源快照不变；`onlyTheCurrentActiveOwningPublisherCanAllocate`。 |
| PAID Zone 调用分配 | 409 GIFT_ZONE_NOT_GIFT；`missingAndPaidZonesAndMalformedPathsAreRejected`。 |
| 正常分配、Seat 与库存 | 五席 GIFT_ALLOCATED、库存减五、容量不变，明细准确；`allocateFiveRestoresNoPaidResourcesAndPersistsExactSeatHistory`。 |
| 库存不足 | 409，不借用其他区域；`insufficientGiftInventoryNeverBorrowsFromAnotherZoneOrPaidSeats`。 |
| 同键相同请求 | 返回原操作，不再扣减；`replayAfterExhaustionAndSessionStartReturnsTheSameOperationAndNoWrites`。 |
| 同键不同数量或 Zone | 409 GIFT_IDEMPOTENCY_CONFLICT；`reusedRequestKeysMustRetainTheEntireAllocationIntent`。 |
| 并发抢最后一席 | 一个 201、一个 409；余量零且只新增一次操作；`competingForTheLastGiftSeatCommitsExactlyOneNewAllocation`。 |
| 同 Zone 与跨 Zone 并发幂等 | 不超分、败方回滚后读取原结果；`concurrentSameZoneRequestsNeverAllocateTwiceOrOversell`、`crossZoneKeyCompetitionRollsBackTheLoserBeforeFreshTransactionReplay`。 |
| SELECT_SEAT 请求赠票座位 | 下单与支付复查均拒绝；`giftZonesAreRejectedAtPublicOrderCreationAndAgainAtPayment`、`publicSelectedSeatCannotReferToAGiftSeatEvenWithAPaidZoneId`。 |
| AUTO_ASSIGN 隔离 | 只占 PAID Seat，GIFT 并发分配独立；`autoAssignmentOnlyUsesThePaidZoneAndConcurrentGiftAllocationStaysIndependent`。 |
| 无领取人绑定 | 仅操作人及实际 Seat，账号、票、限购无新增；`at06RecordsOnlyTheOperatorAndRealSeatsWithoutReceiverAccountsOrTickets`。 |
| 故障注入 | Seat、库存、主操作及明细全部回滚；`everyPartialResourceOrHistoryWriteRollsBackAndTheSameKeyCanRetry`、`specifiedGiftFailureExperimentsLeaveAllSeatsAvailableAndNoOperation`。 |
| 删除待支付用户名单 | 403 QUALIFICATION_DENIED，无资源占用；`aPendingPaymentRechecksMembershipUsingTheSameJwt`。 |
| 删除已支付用户名单 | Ticket VALID、Seat SOLD、Order PAID；`bPaidTicketsAndPaymentReplaySurviveWhitelistChanges`。 |
| 删除已预约用户名单 | 预约 ACTIVE、名额不变；`cSuccessfulReservationSurvivesWhitelistChanges`。 |
| 取消或退款后重新参与 | 新参与按最新名单拒绝，原历史保留；两个 `dCancelThenRebook...` / `dRefundThenNewOrder...` 测试。 |
| 历史权限、分页和完整性 | USER、其他发布者无权读；游标分区正确，明细缺失报冲突；`historyQueriesRequireTheCurrentOwningPublisherAndNeverExposeOtherZones` 等。 |
| 容量巡检 | 三种异常均被检出，合法小于容量不误报；`capacityInspectionFindsEachBrokenCapacityEquation` 等。 |

名单 A–D 在活动级和场次级分别执行，并区分“移除成员”和“取消名单配置”；十四个组合和指定故障 A/B 的细节见 [名单变化与故障验收](day12-whitelist-regression.md)。

## 运行与证据

2026-10-31 专项运行共 **112 项通过，失败 0、错误 0、跳过 0**：GIFT 分配及历史 98 项、名单变化 14 项。MySQL 测试使用随机隔离库，应用 Flyway V7 后运行；没有 DB_PASSWORD 时跳过不能视为通过。

随后运行全项目回归：**52 个测试类、1509 项全部通过，失败 0、错误 0、跳过 0**。包含既有支付、退款、预约、资格与安全测试。GIFT 逐场景对账共 98 项：92 个正常场景均零异常，6 个故意损坏场景均被检出；三类容量造错属于后者，不能把它们报告为正常资源差异。

配置本地 MySQL 与 DB_PASSWORD 后，从项目根目录执行：

```powershell
mvn '-Dtest=GiftAllocationMySqlIntegrationTest,GiftInventorySchemaMySqlIntegrationTest,WhitelistChangeMySqlIntegrationTest' test
```

全项目回归使用 `mvn test`。本次报告与 JSON 证据保存于 [Day 12 验收目录](../verification/day12-completion)，不包含数据库凭据或完整环境属性。

- [AT-06 双向隔离](../verification/day12-completion/day12-at06-paid-gift-isolation.json)、[无领取人绑定](../verification/day12-completion/day12-at06-no-receiver-binding.json)。
- [最后一席竞争](../verification/day12-completion/day12-gift-last-seat-race.json)、[查询与分配锁等待](../verification/day12-completion/day12-gift-history-allocation-lock-wait.json)。
- [历史查询](../verification/day12-completion/day12-gift-history-query.json)、[历史不由当前 Seat 状态重建](../verification/day12-completion/day12-gift-history-not-seat-state.json)。
- [PAID 容量造错](../verification/day12-completion/day12-capacity-inspection-PAID.json)、[GIFT 容量造错](../verification/day12-completion/day12-capacity-inspection-GIFT.json)、[Session 容量造错](../verification/day12-completion/day12-capacity-inspection-SESSION.json)。
- [逐测试对账](../verification/day12-completion/day12-gift-final-reconciliation.json)、[运行汇总](../verification/day12-completion/run-summary.json)。

本日没有扩展 Product、SKU、商品订单或售后、Redis、RabbitMQ、改期、活动取消。RELEASE 仍为可选未实现；后续若增加，必须关联原操作和具体 Seat，同步扩展迁移、锁顺序与历史对账语义。
