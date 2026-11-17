# 限量活动预约与订单系统

Java 后端模块化单体，实现 FREE 预约、付费票务、两种配座、逐票退款、GIFT 独立资源、多 SKU 商品、部分售后、持票限定联动、改期与取消。支付、物流、退货验收为模拟业务；不接入真实收付款。

技术基线：Java 23、Spring Boot 3.5.16、MyBatis Starter 3.0.5、MySQL 8.4.11。Redis 用于公开缓存和短期去重；RabbitMQ + MySQL Outbox 用于真实异步通知、到期触发和限定商品联动。固定基础设施版本及 digest 见 [images.lock.json](deploy/images.lock.json)。

## 从确定版本启动

使用 PowerShell 7、JDK 23、Maven 3.9 和 Docker Desktop Linux containers。Docker 建议至少分配 10 GB 内存；本项目实测环境为 16 GB。先将仓库检出到新目录，切换本地 `v1.0.0-rc1` 或最终 `v1.0.0`；正式状态以 [最终报告](qa/reports/day30-final-acceptance.md) 为准。标签由本地验收创建，未自动推送远程。

以下命令均在新目录根执行。Java/Maven 未加入 PATH 时设置 JAVA_HOME，并给脚本传入 `-MavenCommand` 的实际路径。

```powershell
$commit = (git rev-parse HEAD).Trim()
pwsh -NoProfile -File deploy/scripts/build-image.ps1 -Image las-backend:1.0.0 -SourceCommit $commit
pwsh -NoProfile -File tests/system/start-lab.ps1 -Day 30 -Image las-backend:1.0.0 -SourceCommit $commit
```

构建默认离线，缺少已有依赖时经许可使用 `build-image.ps1 -Online`；环境脚本使用 `--pull never`，先准备 lock 文件列出的固定镜像。构建只打包，不能代替后面的测试。

启动脚本生成新的 `lasday30-<时间>-<随机值>` 项目、独立卷、数据库、随机凭据和回环端口，自动执行空库 Flyway 迁移、启动双实例及 Prometheus/Grafana/Tempo。终端输出 `LAB_DIRECTORY`，将其保存为 `$run`。详细地址和配置在本地 `<run>/.env`，禁止提交或分享此文件。该复现环境叠加验收配置开放回环数据库端口、慢查询采集及全采样 Trace；常规部署只使用 [部署手册](docs/deployment.md) 的基础配置。

```powershell
$run = '粘贴 LAB_DIRECTORY 的绝对路径'
. ./tests/system/common.ps1
$lab = Open-Lab $run
$lab.appA
$lab.appB
Lab-Compose $lab @('ps','--all')
```

两个应用共享 MySQL、Redis、RabbitMQ；管理端口仅在容器网络开放。Prometheus、Grafana、Tempo Web 端口绑定 127.0.0.1。Grafana 使用 admin 和本轮 GRAFANA_PASSWORD。不存在公共演示密码。

历史 V2 在 Day 24 修正过，已有旧 checksum 数据库不能当作本次空库。升级前备份、审核迁移，不自动 `repair` 或清空旧库，见 [部署说明](docs/deployment.md)。

## 执行演示与测试

```powershell
pwsh -NoProfile -File tests/release/run-demo.ps1 -RunDirectory $run
```

演示通过真实 HTTP 创建账号和业务数据，仅对本轮新账号配置发布者/管理员角色。它覆盖两种配座、FREE、GIFT、多 SKU、部分售后、最后有效票退款联动、改期特殊退款、真实截止自动保留及取消批次，并执行全部 SQL。重复运行使用新账号与 requestId，不覆盖此前数据。完整步骤、Apifox 导入和 8 分钟讲解见 [演示手册](docs/demo.md)。

全仓测试会注入真实 Redis/RabbitMQ 停启，只在上述隔离项目运行。先暂停两个应用避免测试与演示任务互相消费；脚本创建并清理本轮临时测试库，保留演示库及卷。

```powershell
. ./tests/system/common.ps1
$lab = Open-Lab $run
Lab-Compose $lab @('stop','app-a','app-b')
try {
  pwsh -NoProfile -File tests/system/run-native.ps1 -RunDirectory $run -SourceCommit $commit -RealOutages -MavenCommand 'mvn' -JavaHome $env:JAVA_HOME
} finally {
  Lab-Compose $lab @('start','app-a','app-b')
}
```

源码、依赖和数据库证据保存在 `<run>/native-*`。计数以 summary.json 和 Surefire XML 为准。老 Day 21 保留演示库对账用例默认跳过；AT 扩展与演示流程仍执行全部 14 份 SQL，不将跳过计为通过。只跑类可传 `-Selection ProductCatalogMySqlIntegrationTest`。详细测试入口见 [最终验收](qa/reports/day30-final-acceptance.md)、[最终 AT 矩阵](qa/reports/day30-at-matrix.md)。

结束时保留数据停止，重启使用相同 `$run`：

```powershell
Lab-Compose $lab @('stop')
# 再次使用时重新 Open-Lab，然后：
Lab-Compose $lab @('up','-d','--pull','never','--no-build')
```

## 设计、接口与运维

- [最终需求](docs/requirements.md)、[架构](docs/architecture.md)、[Schema](docs/schema.md)、[决策](docs/decisions.md)、[锁顺序](docs/locking.md)、[状态机](docs/state-machines.md)。42 张应用表使用逻辑关联；Service、索引及孤儿巡检承担关联一致性成本，这不是所有系统的通用最佳实践。
- [OpenAPI](docs/api/openapi.yaml)、[Apifox/Postman 集合](tests/acceptance/apifox-collection.json)、[API 索引](docs/api/README.md)。
- [运行手册](docs/runbook.md)、[备份恢复](docs/operations.md)、[可观测性](docs/observability.md)。
- [最终缺陷清单](qa/reports/day29-defect-closure.md)、[Day 27 压测](qa/reports/day27-load-report.md)、[Day 28 可靠性](qa/reports/day28-reliability-report.md)、[简历事实](docs/release-facts.md)、[计划进度](docs/schedule.md)。

下单不预占资源，最终支付事务竞争库存、座位和限购。并发限购使用数据库保护行及条件更新；普通 COUNT 查询无法避免两个事务同时通过。Outbox 保证提交后的事件可补发，但确认丢失和 ACK 丢失仍可能重投，消费者必须保持幂等。取消后的账面库存可以因退款恢复，生命周期门禁仍禁止再次销售。

## 已知限制

热点票务 S04/100 VU 在 Day 27 出现 19% 技术错误；部分 50 VU 交易 p95 超过 1 秒参考值，尚不宣称高并发支付容量达标。报告保留成功、业务拒绝和技术错误三个口径。已测读取吞吐和取消批次优化不代表支付容量，也不能当作生产 SLA。

该项目采用单 MySQL、单 Redis、单 RabbitMQ 的本机 Compose，未提供跨机器高可用或零 RPO。备份只能恢复备份时点；消息和任务恢复需保留持久化卷。真实支付、物流、短信、实名与入场核销、积分会员不在一期范围。
