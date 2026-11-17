# 完整演示与八分钟讲解

演示使用发布版本真实 API；请求清单为 `tests/fixtures/demo/plan.json`，由同目录 `build-plan.py` 生成。`tests/release/run-demo.ps1` 执行请求、等待异步完成并校验已提交的数据库事实。SQL 检查均为 SELECT；角色初始化只更新本轮新注册用户，库存、订单、退款、改期和取消均通过 HTTP 完成。

## 准备与自动演练

按 [README](../README.md) 启动独立 Day 30 环境，保存 `$run`。执行：

```powershell
pwsh -NoProfile -File tests/release/run-demo.ps1 -RunDirectory $run
```

每轮创建 Publisher A/B、Buyer A/B/C、Admin。前缀随机避免重复，账号关系、商品和活动结构固定。PAID Activity A 包含自主选座、自动配座场次及 PAID/GIFT 区；FREE Activity B 单独配置；公开与持票商品都有 BLACK-M/WHITE-L。持票商品 BLACK-M 库存为 0，明确调整为 WHITE-L 后才能成交。

凭据与 ID 在本地 `demo-*/variables.private.json`，Apifox 环境在 `apifox-environment.private.json`，不得提交或对外分享。`steps.json` 保存实际状态码、响应、traceId 和等待耗时；`result.json` 仅在全部步骤及对账通过后生成，失败则保留 failure.json。自动保留使用真实两分半截止窗口，没有直接修改 deadline 或 Ticket 状态。

## 使用 Apifox

1. 仅初始化账号和环境：`pwsh -NoProfile -File tests/release/run-demo.ps1 -RunDirectory $run -PrepareOnly`。
2. 在 Apifox 项目「设置 → 导入数据」选择 OpenAPI，导入 `docs/api/openapi.yaml`。该文件使用 JSON 语法保存，符合 OpenAPI 3.0.3。
3. 选择 Postman 导入 `../tests/acceptance/apifox-collection.json`，格式为 Postman Collection 2.1。再导入本轮私密环境文件，选择该环境。官方导入说明见 [Apifox 文档](https://docs.apifox.com/import-postman)。
4. 从第一个请求依次执行，保留后置脚本：它将响应 ID 写入集合变量，后续请求自动引用。第二实例重放使用 baseUrlB；无须手工从数据库找订单明细 ID。
5. 请求说明中标注 poll 的步骤需轮询到预期状态。创建短窗口活动前的预处理脚本生成当前北京时间；请连续执行该组请求，避免准备后停留导致窗口过期。完整自动等待及 SQL 检查由 PowerShell runner 完成，集合不替代对账。
6. 如需全自动继续已准备账号：传 `-PreparedDirectory` 指向本轮 demo 目录。不要同时手工执行与自动执行同一组 requestId。

集合生成格式和接口实际执行分别验证；不把 JSON 格式有效描述成已操作过 Apifox 桌面 UI。

## 实际流程与预期

| 流程 | 核对结果 |
| --- | --- |
| 身份、资源权限 | 未登录 401；买家发布、其他发布者修改资源 403 |
| 发布与名单 | Activity/Session/Zone/Seat 建成后发布；名单、GIFT 独立配置 |
| 票务 | 指定座位和自动配座均支付；第二实例重放返回相同业务结果 |
| FREE | 预约、取消、重新预约；历史保留 |
| 商品 | 缺货 SKU 不替换，用户调整后多 SKU 整单支付；Product 共享限购拒绝超额 |
| 履约与售后 | HTTP 返回 orderItemId；发货、收货、部分质量退款；不可再售不回库 |
| 资格联动 | 第一张退票有余票，不建联动；最后一张退票经真实 MQ，限定明细退款，普通明细保留 |
| 改期 | 一个 Ticket 确认保留，另一个零手续费特殊退款；另一短窗口场次真实到期自动保留 |
| 取消 | 场次立即禁止支付，批次最终完成，其他场次有效；FREE/PAID 活动取消分别处理 |
| 查询与收敛 | 通知可查询，缓存重复读取；即时 Outbox/失效任务完成，14 份 SQL 正常差异为 0 |

## 八分钟讲解时间轴

先完整运行自动演练，打开 steps.json、最终对账和可观测页面，再按以下节奏展示；无需在八分钟内重新跑全仓和故障实验。

| 时间 | 展示与讲解 |
| --- | --- |
| 00:00–00:40 | README 架构、登录、USER/PUBLISHER/ADMIN 与跨资源拒绝 |
| 00:40–01:40 | Activity A 两场次、区域/座位、名单覆盖和独立 GIFT；展示请求响应 |
| 01:40–02:40 | FREE 重约、两种选座、支付重放；解释下单不预占 |
| 02:40–03:30 | 逐票退款和原价政策快照，余票保持有效 |
| 03:30–04:40 | 商品明细 ID、跨 SKU 共享限购、部分售后及不可再售库存 |
| 04:40–05:40 | 最后有效票退款 → Outbox → Consumer → 限定明细售后；普通明细隔离 |
| 05:40–06:40 | 改期保留/特殊退款/自动保留，取消事件、批次和禁交易 |
| 06:40–08:00 | 双实例 Prometheus/Grafana、真实 traceId、Day 28 ACK 前崩溃恢复；说明热点支付容量限制 |

故障演示使用已保存的真实实验记录；不现场删除卷或改数据库伪造成功。完整失败恢复步骤和数据边界见 [可靠性报告](../qa/reports/day28-reliability-report.md)。
