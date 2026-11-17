# Day 30 最终 AT 与高风险矩阵

被测 RC：`6aa8e757519ff1a6313c1e5c9007b107c7b45603`，独立检出，全仓 1790 项、1789 PASS、0 FAIL/ERROR、1 SKIPPED。20 AT 与 8 类高风险全部 PASS；124 份提交后数据库快照覆盖 AT 及十类故障，正常差异为 0。

逐方法、JUnit XML 和提交后证据路径见 [机器矩阵](day30-at-results.json)。每份快照执行全部 14 SQL，消息诊断记录不算不变量错误。p16 注入异常在用例中准确检出，恢复后捕获正常快照。

| AT | 规则 | 方法调用数 | 提交后快照数 | 结果 |
| --- | --- | ---: | ---: | --- |
| AT-01 | 同活动不同场次可分别免费预约；同场次最多一笔有效预约 | 2 | 2 | PASS |
| AT-02 | 最后一个免费名额最多一个成功 | 1 | 1 | PASS |
| AT-03 | 取消释放名额；重新预约不享有优先权 | 3 | 3 | PASS |
| AT-04 | 显式空白名单拒绝；未配置执行继承或公开规则 | 14 | 14 | PASS |
| AT-05 | 同账号两笔各 3 张订单竞争场次限购 4，不超限 | 4 | 4 | PASS |
| AT-06 | GIFT 独立库存且不绑定平台买家 | 2 | 2 | PASS |
| AT-07 | 最后一张付费票仅一个成交，失败事务无局部扣减 | 5 | 5 | PASS |
| AT-08 | 停售阻止新建票单，未过期旧单仍可支付 | 1 | 1 | PASS |
| AT-09 | 仅退指定 Ticket，使用原成交价和冻结政策 | 1 | 1 | PASS |
| AT-10 | 并发退款同一票只生效一次 | 3 | 3 | PASS |
| AT-11 | 黑 M 缺货不替换白 L，调整清单需再次确认 | 2 | 2 | PASS |
| AT-12 | 多 SKU 缺货整单回滚，回滚后安全关闭旧单 | 3 | 3 | PASS |
| AT-13 | 同 Product 两 SKU 共享限购 2 | 2 | 2 | PASS |
| AT-14 | 已发货部分售后，验收后仅可再售部分回库 | 3 | 3 | PASS |
| AT-15 | 最后有效票退款经 Outbox/RabbitMQ 仅联动失去资格的商品行 | 5 | 5 | PASS |
| AT-16 | 发布后配置冻结，时间变更仅走专用改期 API | 28 | 28 | PASS |
| AT-17 | 特殊退款截止 min(公告+7天,新开始)，零手续费且已受理申请不失效 | 6 | 6 | PASS |
| AT-18 | 到期未响应自动保留，不写为用户主动确认 | 2 | 2 | PASS |
| AT-19 | 取消场次立即停止交易，其他场次隔离 | 2 | 2 | PASS |
| AT-20 | 取消退款单项失败可查可重试，成功项不重复执行且不能重新销售 | 3 | 3 | PASS |

AT 行可能引用同一测试方法，不应相加作为全仓测试总数。新加入的 `orderResponseItemIdsEnableFulfillmentAndAfterSaleWithoutDatabaseLookup` 归属 AT-14，客户端仅用 HTTP 返回的明细 ID 完成履约与售后。

| 风险 | 内容 | 结果 |
| --- | --- | --- |
| RISK-SECURITY | 跨账号订单、跨 Publisher、禁用账号、伪造角色/金额；真实过滤器和数据库所有权检查 | PASS |
| RISK-SEATS | 自主选座、自动配座、连座优先、非连座确认、同座竞争 | PASS |
| RISK-PAYMENT-TIME | 创建不占资源、支付占用、停售不等于到期、锁等待跨到期边界 | PASS |
| RISK-REFUND-AMOUNTS | 48h 优先、30/14/10 天的 ±1ms、每张票 HALF_UP 舍入和原成交价 | PASS |
| RISK-AFTERSALE | 七日模板、特殊模板与质量问题、重复验收和发货竞态 | PASS |
| RISK-QUALIFICATION | ACTIVITY/SESSION 范围、名单变更、商品支付与最后票退款争用 | PASS |
| RISK-LIFECYCLE | 多次改期与独立窗口、取消整个活动、历史场次隔离、并发取消与支付 | PASS |
| RISK-MESSAGING | Outbox 补发、已提交未 ACK 的重投、毒消息/DLQ、重放和资格事件乱序 | PASS |

两项真实停机已开启并实际执行；唯一跳过为 `Day21ReconciliationMySqlIntegrationTest.allSqlScriptsUseOneReadOnlySnapshotAndSaveEveryResultSet`，其旧演示库不属于本轮运行目标。本轮 AT 快照、双实例业务与演示仍分别执行全部对账脚本。
