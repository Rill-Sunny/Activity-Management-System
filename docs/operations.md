# 备份、恢复和部署排障

命令在仓库根目录的 PowerShell 7 中执行。默认项目名为 `limited-activities-system`；验收使用独立的 `lasday24-*` 项目名。所有操作都指定项目名和对应配置，防止操作另一套环境。

## 备份数据库

```powershell
pwsh -NoProfile -File deploy/scripts/backup-db.ps1 -Project limited-activities-system -EnvFile deploy/.env -OutputFile backups/business-20261112.sql
```

脚本在 MySQL 容器内执行 `mysqldump --single-transaction`，保存 Schema、数据、触发器、例程和事件。密码从容器环境读取，不出现在宿主机命令参数中。备份通过字节流写入宿主机，避免 PowerShell 文本重编码。旁边的 JSON 记录数据库版本、起止时间和 SHA-256。

备份期间禁止 DDL。需要严格比较所有表时，先暂停两个应用，完成备份和数据快照后恢复。备份只覆盖备份时点的数据，不能恢复之后才提交的订单。

## 恢复到独立新库

```powershell
pwsh -NoProfile -File deploy/scripts/restore-db.ps1 -Project limited-activities-system -EnvFile deploy/.env -BackupFile backups/business-20261112.sql -TargetDatabase las_restore_20261112
```

目标名称必须以 `las_restore_` 开头。脚本校验备份摘要，拒绝应用当前库和任何已存在的目标库；不会先删除旧库。恢复失败时保留目标供排查，下一次使用新的库名。

恢复结束只表示 SQL 导入完成。验收还必须比较源库和目标库各表的主键排序结果摘要，执行 `tests/sql` 全部脚本，确认订单、Ticket、库存、限购和逻辑关联均正确。自动执行见 `tests/deployment/run-day24-tests.ps1` 的 Recovery 阶段。`messaging_reconciliation.sql` 输出业务处理诊断，不能把其正常记录数计为不变量差异。

## 查看状态和日志

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yaml ps --all
docker compose --env-file deploy/.env -f deploy/compose.yaml logs --tail 200 migrate app-a app-b
docker compose --env-file deploy/.env -f deploy/compose.yaml logs --tail 200 mysql redis rabbitmq
```

应用日志包含实例身份、requestId、traceId 和 spanId。容器日志单文件上限 10 MB，每个容器保留 3 份。

## 处理启动失败

| 现象 | 检查步骤 |
|---|---|
| 缺少配置变量 | 对照 `.env.example` 补齐；不要用示例占位符启动 |
| `migrate` 非零退出 | 先读 migrate 日志；确认空库、数据库权限和 V2 历史校验；不自动 repair |
| 应用等待依赖 | 查看 MySQL、Redis、RabbitMQ 健康检查输出 |
| 主机端口被占用 | 修改 `.env` 端口后重建容器；不停止未知进程 |
| 数据库连接失败 | 容器内主机名应为 mysql；核对卷中原账号密码与 `.env` 是否一致 |
| RabbitMQ 认证失败 | 初始化用户只在空卷生效；改 `.env` 不会重置已有 Broker 用户 |
| Prometheus target 为 DOWN | 核对专用密码、9091 内网端口、应用 docker profile 和 `/actuator/prometheus` |
| Tempo 没有 trace | 启动 observability 并设置 TRACING_ENABLED=true，检查 OTLP 导出日志 |
| 容器退出 137 | 检查 Docker Desktop 可用内存及容器内存限额 |

## 运行独立部署验收

先按部署手册构建 `las-backend:day24`。确认允许启动本轮隔离资源后执行：

```powershell
pwsh -NoProfile -File tests/deployment/run-day24-tests.ps1
```

脚本创建随机项目名、全新命名卷和随机凭据，使用可用回环端口。证据写入 `qa/verification/day24/<RunId>/`。支持 `-Stage Start`、`Business`、`Recovery`、`Observe`；后续阶段必须传 `-RunDirectory`。

脚本不下载镜像、不删除卷，也不覆盖开发数据库。完成后保留环境与数据供复核；停止时用 context.json 中的项目名和 envFile 执行 `down`。失败后先查看该阶段日志，修复后使用明确的阶段继续；业务阶段写入固定夹具，完整业务复跑需要新项目和新卷。

本次真实停止再启动用于验证持久化，不等同于 Day 28 的交易中途 Redis 或 RabbitMQ 停机故障矩阵。
