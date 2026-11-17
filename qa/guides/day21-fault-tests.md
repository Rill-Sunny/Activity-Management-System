# 在 IDEA 验证 Redis 和 RabbitMQ 真实停机恢复

本指南执行此前被忽略的两个用例。商品专项已反馈 126 项通过、1 项停机用例未执行；MQ 普通可靠性测试也已反馈通过。真实停机需要单独补充证据。

现有 `ComposeServiceFault` 只操作已经运行的 Docker Compose 服务 `redis` 或 `rabbitmq`，不能控制 Windows 原生服务。它自动执行 stop、故障期断言、start 和恢复断言。不要在点击运行前手动停止服务，否则前置检查和缓存预热会失败。

## 准备已有依赖

在项目根目录打开 PowerShell，检查本机 Docker 引擎和 Compose：

```powershell
docker version
docker compose version
docker image inspect redis:8.10.3 rabbitmq:4.1.8-management --format '{{.RepoTags}}'
```

`docker version` 需要同时显示客户端和服务端。若命令不存在、引擎未启动或镜像缺失，先完成环境准备；涉及安装 Docker 或下载镜像时，按本项目约定先确认。本文不会自动安装或拉取镜像。本次文档更新时，执行终端的 PATH 未找到 `docker`，尚未实际启动或验证容器。

镜像版本沿用已有 `deploy/compose.yaml`。本次新增的 [专用 Compose 文件](../../deploy/compose.day21-faults.yaml) 只有 Redis 和 RabbitMQ，使用项目名 `las-day21-faults` 和独立数据卷。端口是 Redis `16379`、RabbitMQ AMQP `5673`、RabbitMQ 管理页面 `15673`，均绑定 `127.0.0.1`，可与现有原生服务并存。端口必须空闲。

MySQL 继续使用已经通过商品测试的连接和账号。测试自行创建随机隔离库，账号需要创建、删除这些测试库及访问原有锁诊断表的权限。不要把数据库改到尚未准备的端口。

## 启动专用容器

首次准备时复制环境文件；已经存在时保留原文件：

```powershell
if (-not (Test-Path -LiteralPath 'deploy/.env.day21-faults')) {
    Copy-Item -LiteralPath 'deploy/.env.day21-faults.example' -Destination 'deploy/.env.day21-faults'
}
```

在本地 `deploy/.env.day21-faults` 中把 `DAY21_RABBITMQ_PASSWORD` 改为测试密码，用户名可保留 `day21-test`。该文件已被现有 Git 忽略规则覆盖。不要覆盖日常开发环境的 `deploy/.env`。首次初始化后的 Broker 账号保存在数据卷中；后续修改环境文件不会自动修改已有账号，参见 [RabbitMQ 初始用户与账号管理](https://www.rabbitmq.com/docs/access-control#default-state)。

在同一个 PowerShell 窗口定义路径并启动：

```powershell
$day21Compose = (Resolve-Path 'deploy/compose.day21-faults.yaml').Path
$day21EnvFile = (Resolve-Path 'deploy/.env.day21-faults').Path
$day21ComposeArgs = @('compose', '--project-name', 'las-day21-faults', '--env-file', $day21EnvFile, '-f', $day21Compose)

docker @day21ComposeArgs config --quiet
docker @day21ComposeArgs up -d --pull never --no-build --wait --wait-timeout 120 redis rabbitmq
docker @day21ComposeArgs ps
docker @day21ComposeArgs exec -T redis redis-cli ping
docker @day21ComposeArgs exec -T rabbitmq rabbitmq-diagnostics -q ping
```

确认配置检查成功、两个容器 healthy、Redis 返回 PONG、RabbitMQ 诊断成功后再测试。`--pull never` 禁止此启动步骤下载镜像，`--wait` 等待服务就绪，参见 [Docker Compose up](https://docs.docker.com/reference/cli/docker/compose/up/)。若某一步失败，处理该错误后重试，不继续执行后面的步骤。

## 复制 IDEA 配置并设置变量

在 **Run → Edit Configurations** 中复制现有配置，保留 JDK 23、模块 classpath 和 MySQL 连接。原来的日常配置继续保持 `RUN_DAY21_FAULT_TESTS=false`。

| 新配置名称 | 从哪个配置复制 | Test kind | Class | Method |
|---|---|---|---|---|
| `Day21-Redis-Fault` | `Day21-Product` | `Method` | `com.limitactivitysystem.activity.integration.ProductCatalogMySqlIntegrationTest` | `realRedisStopPreservesTradesAndRecoveryDrainsDurableInvalidation` |
| `Day21-RabbitMQ-Fault` | `Day21-RabbitMQ` | `Method` | `com.limitactivitysystem.messaging.integration.RabbitMqOutboxReliabilityMySqlIntegrationTest` | `realBrokerStopRetainsCommittedOutboxAndRecoveryDeliversIt` |

Working directory 设为项目根目录。Method 类型用于只运行一个测试方法，参见 [IDEA JUnit 配置](https://www.jetbrains.com/help/idea/run-debug-configuration-junit.html)。

在两个新配置的 **Environment variables** 中添加或修改下列内容。路径示例对应当前工作区；如果移动项目，改为前面 PowerShell 解析出的实际绝对路径。GUI 中路径值无需额外包一层引号。

```text
RUN_DAY21_FAULT_TESTS=true
DAY21_COMPOSE_PROJECT=las-day21-faults
DAY21_COMPOSE_FILE=D:\Computer_Science\aaaSaucedflavoredPancake\Backend_Study\Java\practice\limited-activities-system\deploy\compose.day21-faults.yaml
DAY21_COMPOSE_ENV_FILE=D:\Computer_Science\aaaSaucedflavoredPancake\Backend_Study\Java\practice\limited-activities-system\deploy\.env.day21-faults
REDIS_HOST=127.0.0.1
REDIS_PORT=16379
RABBITMQ_HOST=127.0.0.1
RABBITMQ_PORT=5673
RABBITMQ_USER=day21-test
RABBITMQ_PASSWORD=<本地环境文件中 DAY21_RABBITMQ_PASSWORD 的实际值>
```

保留已有正确的 `DB_PASSWORD`、数据库 URL 和数据库用户名。`Day21-RabbitMQ-Fault` 还必须保留 `RUN_MQ_RELIABILITY_TESTS=true`，否则整个类被忽略。Redis 方法只依赖类级 DB_PASSWORD 和方法级停机开关；复制来的其他普通回归开关可以保留。

IDEA 中的 `RABBITMQ_USER` 和 `RABBITMQ_PASSWORD` 必须与专用容器的账号一致，不能继续使用另一个 Windows Broker 的凭证。客户端端口也必须指向被 Compose 控制的容器：Redis 16379、RabbitMQ 5673。否则会出现“容器已停止，但测试仍连着原生服务”的无效实验。

这些变量不要写到 VM options。也不要增加 JUnit 条件停用参数。若 Docker 刚加入 PATH，重启 IDEA，使测试进程能找到 `docker`。两个新配置如保存到 `.run`，使用 `Day21-Redis-Fault.local.run.xml` 和 `Day21-RabbitMQ-Fault.local.run.xml`，现有规则会忽略其中的本地凭证。

## 串行运行两个停机用例

1. 停止其他连接这套隔离 Redis/RabbitMQ 的应用及测试进程。
2. 选择 `Day21-Redis-Fault` 并运行。测试自动停止、恢复 Redis；单方法结果应为 1 passed、0 failed、0 skipped。
3. 在原 PowerShell 窗口执行 `docker @day21ComposeArgs ps`，确认 Redis、RabbitMQ 均已恢复 healthy。
4. 选择 `Day21-RabbitMQ-Fault` 并运行。测试自动停止、恢复 RabbitMQ；单方法结果应为 1 passed、0 failed、0 skipped。
5. 再次检查两个服务 healthy，保存结果及证据。不要并行运行两个故障配置。

每个方法的 JUnit 总超时为 180 秒。停机和启动由代码控制，不需要人工把握停止时机，也不要提前执行 `docker compose stop`。Docker 的 [stop](https://docs.docker.com/reference/cli/docker/compose/stop/) 保留容器，可由 [start](https://docs.docker.com/reference/cli/docker/compose/start/) 恢复；本测试不用删除容器或数据卷的方式模拟故障。

两个方法具体验证：

| 用例 | 故障期间 | 恢复之后 |
|---|---|---|
| Redis | 缓存访问失败，活动和 Product 查询回源；SKU 初始 5，重复支付购买 2 后库存仍为 3，重复部分退款 1 后库存为 4、限购计数为 1；商品下架产生带错误原因的持久失效任务；顺序故障操作耗时小于 30 秒 | 等待 Redis PONG；重试本次持久任务，待处理数为 0，旧缓存删除，下架商品查询返回 404，对账无异常 |
| RabbitMQ | 已提交 Outbox 事件保留为 PENDING，记录发布失败原因，consumer_process 为 0 | 等待 Broker 就绪后补发为 SENT；队列中收到同一 event_id；通知和 consumer_process 各 1 条 |

RabbitMQ 停机用例恢复后的消费通过测试辅助方法调用真实消费者；真实 Listener 的停启积压回归由同类另一个用例覆盖。Redis 的 30 秒断言是本地顺序操作的耗时界限，不代表高并发容量验收。旧值回填竞争与失效扫描已有普通回归，不能用停机用例替代它们。

## 保存验收证据

保存本轮 IDEA 测试结果、控制台实际退出码、运行时间，以及 `git rev-parse HEAD` 和工作区修改摘要。不要把上一轮的控制台日志当作本轮证据。JUnit 直接运行不一定生成 Surefire XML；现有 `collect-day21-results.ps1` 的 Finish 阶段只用于 Maven 产生的 Surefire 报告。

运行后检查并按本轮时间归档：

| 文件或目录 | 内容 |
|---|---|
| `target/verification/day21-real-redis-stop.json` | 停机耗时、持久失效任务 ID、库存、限购、回源指标和对账结果 |
| `target/day21/mq/broker-stopped.json` | 停机期间 Outbox、消费记录和通知状态 |
| `target/day21/mq/broker-recovered.json` | 恢复后同一事件的发布和业务效果 |
| `target/day21/compose/` | 测试内部执行 Compose 的输出日志 |
| `target/day21/mq-queues/` | 测试专属队列的结束快照 |

按 event_id 对齐 RabbitMQ 的两份 JSON，确认是同一事件补发且只生效一次。自动测试结束会清理随机数据库及专属 Rabbit 资源，不能事后查空库代替这些执行期证据。独立演示库全 SQL 对账、全仓 Maven test 和 Tempo 导出按 [主回归交接](day21-regression.md) 继续完成。

只有两个实际停机方法执行成功，才将报告中的“未执行”改为“通过”。若把专用配置改回 Class，且其余普通开关均开启，商品类预期为 127 项通过、MQ 类预期为 9 项通过；这仍是两个专项类，不是全仓全量测试。

## 异常退出后恢复

测试通常通过 try-with-resources 尝试恢复已停止的服务。若 IDEA 强制停止 JVM，使用同一组路径和项目名恢复现有容器：

```powershell
docker @day21ComposeArgs start --wait --wait-timeout 120 redis rabbitmq
docker @day21ComposeArgs ps
```

如果重新打开了 PowerShell，先重新执行定义 `$day21Compose`、`$day21EnvFile` 和 `$day21ComposeArgs` 的代码。保存失败日志后检查服务状态，再决定复跑。不要删除数据卷以掩盖恢复问题。
