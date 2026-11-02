# Day 14 商品发布、SKU 与资格验证

本次实现用户第 13～18 项：可信发布者创建草稿、SKU 管理、上架/下架、独立商品名单、三类当前资格查询和公开展示。[API 契约](api/products.md) 列出请求、响应、状态与错误；订单、支付和联动仍为后续阶段。

[V8](../src/main/resources/db/migration/V8__create_product_catalog.sql) 新建 product、sku、product_whitelist_member，当前运行模型共二十张业务表，仍无物理 FOREIGN KEY。原十七张表定义保持不变，重复 migrate 执行零条迁移。商品订单、user_product_purchase 和 user_activity_qualification_guard 没有提前建立。

## 实测范围

[ProductCatalogMySqlIntegrationTest](../src/test/java/com/limitactivitysystem/activity/integration/ProductCatalogMySqlIntegrationTest.java) 经过真实 Security、Controller、Service、MyBatis 与 MySQL，不用测试外层事务遮蔽业务提交。

| 场景 | 结果 |
|---|---|
| Publisher 从自己的 Activity 创建商品 | 201 DRAFT，publisherId 来自 CurrentUserProvider，客户端不能伪造。 |
| USER、其他发布者、停用账号 | 不能管理资源；事务内再检查当前角色与状态。 |
| DRAFT、上架、下架与重新上架 | 未上架不公开，缺 SKU 不上架；重新上架保留 firstOnSaleAt。 |
| SKU 配置 | DRAFT 可创建及完整修改；ON_SALE/OFF_SALE 均拒绝价格/库存修改，原快照不变。 |
| BLACK-M 库存 0、WHITE-L 库存 20 | 精确查询 BLACK-M 仍为 0，未选择 WHITE-L 替代。 |
| 小数库存、字符串金额、金额超精度、库存溢出及额外字段 | 400，未产生部分写入。 |
| 商品独立名单 | 空名单拒绝，增加/删除成员改变当前结果，活动名单不提供替代资格；PUBLIC 不继承活动空名单。 |
| 两张已支付票退一张 | Order PARTIALLY_REFUNDED，剩一张 VALID 仍有资格；最后一张退款后失去资格。 |
| ACTIVITY 与 SESSION | 同活动其他场次可提供 ACTIVITY 资格；SESSION 仅接受指定场次，其他活动无效。 |
| 错误地仅有 PAID 订单状态 | 故意损坏的中间夹具中，没有 VALID Ticket 即拒绝；随后恢复数据再正常对账。 |
| 免费预约、赠票分配 | 不提供普通持票资格。 |
| 并发同名 SKU | 只有一行库存，响应 201/409；未建立两个同名库存池。 |
| 名单变更与资格查询 | 观察 Product 行的真实 InnoDB 锁等待，读者先得到原资格，变更提交后新查询拒绝。 |
| 数据库约束 | 类型/范围 NULL 组合、未知枚举、非正限购、非法发布时点、负库存及超库存均被拒绝。 |
| SQL 后故障 | Product INSERT、SKU INSERT、上架 UPDATE 后注入异常，事务完整回滚，资源快照不变。 |
| 零 FK 逻辑关联 | 三种故意孤儿能被 product_invariants.sql 检出。 |

资格预览本身不扣 SKU、不占 Product 额度，不向外暴露查询任意用户的身份参数。部分退款回归通过真实创建、支付、退款受理和处理 API 产生结果，未用 PAID 状态直接冒充有效票。

## 结果与复跑

2026-11-02 全项目运行 **55 个测试类、1587 项全部通过，失败 0、错误 0、跳过 0**，包含商品专项 57 项。结果见 [全量运行汇总](verification/day14-products/full-run-summary.json)。

全量之后补强商品测试结束前的预约与 GIFT 历史巡检，再独立重跑商品专项，具体计数与源码摘要见 [专项汇总](verification/day14-products/focused-run-summary.json)。正常商品场景要求目录、退款、预约和 GIFT 对账无异常；三个故意损坏的目录场景要求明确检出，不能把造错差异当作正常结果。

补强后专项仍为 **57 项通过**：54 个正常场景零差异，3 个故意损坏场景均检出。随后 `mvn -DskipTests package` 打包成功，归档中确认包含商品 Controller、资格 Service 和 V8 迁移，见 [构建摘要](verification/day14-products/build-summary.json)。打包未重复执行测试，其测试依据为上述全量及专项报告。

配置 MySQL 和 DB_PASSWORD 后执行：

```powershell
	mvn '-Dtest=ProductCatalogMySqlIntegrationTest' test
	mvn test
```

MySQL 测试被跳过不算通过。测试使用随机隔离库与真实 Flyway，显式排除含固定 USE 的历史 V2 seed。证据只归档摘要文本及业务 JSON，不归档包含环境属性的原始 Surefire XML。

- [V7→V8 迁移](verification/day14-products/day14-product-migration.json)。
- [SKU 不互换](verification/day14-products/day14-sku-no-substitution.json)。
- [有效票与部分退款资格](verification/day14-products/day14-product-valid-ticket-qualification.json)。
- [Product 行真实锁等待](verification/day14-products/day14-product-whitelist-lock-wait.json)。
- [逐场景目录巡检](verification/day14-products/day14-product-reconciliation.json)。

本次不宣称 AT-11 的商品订单缺货流程已完成：当前验证的是 SKU 资源隔离及资格前置切片。商品支付与最后一张票退款的共同 guard、Outbox 和联动任务仍须按 [设计协议](locking.md#product-qualification-and-payment-design) 实施并验证。

## Day 14 acceptance

第 19～24 项最终验收增加十九个用例，本轮商品 76 项、安全 44 项，共 **120 项全部通过，失败 0、错误 0、跳过 0**。没有改动生产业务代码或 V8 迁移，之前的 1587 项全量结果作为历史记录保留；本轮没有重新运行全量测试。

| 要求 | 本轮实测证据 |
|---|---|
| 发布者资源归属 | A 使用 B 的 activityId 创建 Product 返回 403；A 向 B 的 Product 创建/修改 SKU、上架/下架、管理成员或读取管理详情均返回 403。所有拒绝前后目录及票务快照相同。 |
| 子资源不能借父路径越权 | A 用自己的 Product 路径和 B 的 skuId 修改，返回 404 SKU_NOT_FOUND，B 的 SKU 原价和库存保持不变。 |
| PUBLIC | 普通 ACTIVE 账号允许。 |
| PRODUCT_WHITELIST | 独立名单内允许，名单外拒绝；活动名单不能代替商品名单。 |
| ACTIVITY 持票 | 真实 VALID Ticket 允许；通过正式退款流程只剩 REFUNDED Ticket 时拒绝。 |
| SESSION 持票 | 其他场次 VALID Ticket 拒绝，指定场次 VALID Ticket 允许。 |
| 两种售后模板 | 数据库值、API 枚举和版本一致；SPECIAL 明确展示质量问题、限定资格丧失和取消的边界，没有通用 returnable=false 字段。模板不阻止 PUBLIC 当前资格。 |
| Day 14 范围 | 商品订单、支付、发货入口不存在；对应订单、额度、guard 表没有提前落库，读取目录/资格未扣 SKU 库存或增加额度。 |

资格预览的业务拒绝为 HTTP 200、allowed=false 和对应 reason，不与认证或资源权限的 HTTP 401/403 混淆。模板用例只验证存储/展示语义，不声称质量售后、取消联动或收发货已可执行。

本轮目录对账共 76 场景：73 个正常场景零差异，3 个故意损坏夹具均被检出；正常场景同时执行退款、免费预约和 GIFT 对账。迁移仍为 V8，只有 product、sku、product_whitelist_member 三张新增表，当前二十张业务表、零物理外键。未清楚冻结的商品交易模型继续留在后续阶段。

独立执行命令：

```powershell
	mvn '-Dtest=ProductCatalogMySqlIntegrationTest,SecurityAccessTest' test
```

原始摘要与逐场景业务 JSON 在 [最终验收目录](verification/day14-acceptance)，[执行汇总](verification/day14-acceptance/run-summary.json) 同时记录测试源码与生产源码摘要，避免把新测试结果归给旧源码。

七个资格场景文件名为 `day14-qualification-matrix-*`，八个归属操作为 `day14-owner-denied-*`，模板为 `day14-policy-template-*`。[Day 14 范围证据](verification/day14-acceptance/day14-catalog-scope.json) 单独记录未进入后续交易切片。跨 Publisher 商品订单在设计上拒绝，同 Publisher 能否跨 Activity 仍待确认；由于订单未实现，本轮不伪称该订单规则已经经过交易接口测试。
