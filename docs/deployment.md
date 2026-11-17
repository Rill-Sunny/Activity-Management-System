# 在 Docker 中启动完整项目

本手册使用 PowerShell 7、Docker Desktop 的 Linux 容器、JDK 23 和 Maven 3.9。IDEA 自带 Maven 可以作为构建工具。Docker 内运行同一个应用镜像的两个实例；本机 Maven 只负责生成 JAR。

## 准备配置

1. 启动 Docker Desktop，确认 Docker 使用 Linux containers。
2. 在仓库根目录执行 `pwsh -NoProfile -File deploy/scripts/new-environment.ps1`。
3. 编辑 `deploy/.env` 中的数据库名称、用户名及端口。脚本已生成随机密码和 Base64 JWT 密钥，不输出秘密值。

`.env` 已被 Git 忽略。不要提交该文件，也不要把 `docker compose config` 的完整展开结果保存到公共报告；该结果包含密码。公开检查使用 `config --quiet`，验收脚本只保存经过筛选的拓扑。

已有 `deploy/.env` 时脚本拒绝覆盖。旧版 `MYSQL_APP_PASSWORD`、`RABBITMQ_USER` 配置需要迁移到 `.env.example` 中的 `MYSQL_PASSWORD`、`RABBITMQ_USERNAME`。JWT 的实际配置名是 `JWT_SECRET_BASE64`，解码后至少 32 字节。

## 构建应用镜像

确认 `JAVA_HOME` 指向 JDK 23，Maven 可通过 `mvn` 执行。第一次构建可能需要下载 POM 中的运行依赖和 Maven 插件。

```powershell
pwsh -NoProfile -File deploy/scripts/build-image.ps1 -Online
```

使用 IDEA 的 Maven 时，传入实际路径：

```powershell
pwsh -NoProfile -File deploy/scripts/build-image.ps1 -MavenCommand '你的IDEA目录/plugins/maven-plugin/lib/maven3/bin/mvn.cmd' -JavaHome '你的JDK23目录' -Online
```

依赖已缓存时去掉 `-Online`，脚本使用 Maven 离线模式。此步骤执行 `package -DskipTests`，不代表测试通过。Dockerfile 只复制可执行 JAR，以 UID 10001 运行；镜像中没有源码、本机 Maven 仓库、IDEA 配置或 `.env`。

`deploy/images.lock.json` 记录实际拉取的镜像摘要。Dockerfile、Compose 和 `.env.example` 使用同一组固定摘要，运行时为 Temurin 23.0.2+7。更新镜像时需要重新做部署验收。

## 从空库启动核心服务

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yaml --profile core config --quiet
docker compose --env-file deploy/.env -f deploy/compose.yaml --profile core up -d --build
docker compose --env-file deploy/.env -f deploy/compose.yaml ps --all
```

`core` 包括 MySQL、Redis、RabbitMQ、一次性 `migrate` 和 `app-a`、`app-b`。数据库就绪后，迁移进程单独执行 Flyway V1～V18；成功退出后才启动两个应用。应用的 Docker profile 禁用启动迁移。`migrate` 显示 `Exited (0)` 是预期结果。

**旧数据库迁移注意：** 原 V2 写死 `USE limit_activity_system`，且开启事务后没有提交。本次改为使用连接选定的数据库并明确提交、恢复时区。因此已经执行旧 V2 的 Flyway 库可能出现 checksum 不匹配。本手册验收使用全新卷；不要对原业务库自动执行 `repair`。先备份、核对历史与实际数据，再单独制定旧库升级方案。

## 启动观测服务

把 `deploy/.env` 的 `TRACING_ENABLED` 改为 `true`，然后执行：

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yaml --profile core --profile observability up -d --build
```

`observability` 包括 Prometheus、Grafana 和 Tempo。Prometheus 使用专用凭据访问两个应用的内部 9091 指标端口，并从 RabbitMQ 内部 exporter 采集队列深度。Grafana 自动配置两种数据源和 `LAS Backend Overview` 面板。Day 25 的有效配置位于 `deploy/observability/`，指标与告警含义见 [可观测性参考](observability.md)，实际验收状态以 [Day 25 报告](../qa/reports/day25-observability-test.md) 为准。

仅运行 core 时将 `TRACING_ENABLED` 设为 `false`，避免持续向不存在的 Tempo 导出。

## 检查健康与访问地址

```powershell
Invoke-RestMethod http://127.0.0.1:8081/actuator/health
Invoke-RestMethod http://127.0.0.1:8082/actuator/health
docker compose --env-file deploy/.env -f deploy/compose.yaml exec app-a java -version
docker compose --env-file deploy/.env -f deploy/compose.yaml logs --tail 100 app-a app-b migrate
```

两个健康响应均应为 `{"status":"UP"}`。公开健康检查包含应用就绪、MySQL、Redis 和 RabbitMQ，依赖不健康时返回 503，不输出连接详情。

默认地址如下：

| 服务 | 本机地址 |
|---|---|
| 应用 A | http://127.0.0.1:8081 |
| 应用 B | http://127.0.0.1:8082 |
| Prometheus | http://127.0.0.1:9090 |
| Grafana | http://127.0.0.1:3000，用户名 admin，密码来自本地配置 |
| Tempo | http://127.0.0.1:3200 |

常规部署不映射 MySQL、Redis、RabbitMQ 或应用指标端口。容器通过 `mysql:3306`、`redis:6379`、`rabbitmq:5672` 通信。验收专用 override 只为隔离测试开放本机回环端口，不应加入日常启动命令。

## 停止与恢复

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yaml --profile core --profile observability down
docker compose --env-file deploy/.env -f deploy/compose.yaml --profile core --profile observability up -d
```

普通停止不加 `-v`。MySQL、Redis AOF、RabbitMQ 和观测服务各有独立命名卷；RabbitMQ 使用固定 hostname，避免重建容器时更换节点数据目录。

备份、隔离恢复及故障定位见 [运维手册](operations.md)。自动验收命令和实测结果见 [Day 24 报告](../qa/reports/day24-deployment-test.md)。

## 配置依据

迁移成功等待及依赖健康检查依据 [Compose 启动顺序](https://docs.docker.com/compose/how-tos/startup-order/)。两个服务组合依据 [Compose profiles](https://docs.docker.com/compose/how-tos/profiles/)。Prometheus 运行依赖及端点依据 [Spring Boot 3.5 指标文档](https://docs.spring.io/spring-boot/3.5/reference/actuator/metrics.html)。历史脚本变更的校验行为见 [Flyway Validate](https://documentation.red-gate.com/flyway/reference/commands/validate)。
