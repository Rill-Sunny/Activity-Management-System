# 运行并发验证

Day 9 免费预约的 AT-01～AT-03、重复预约、重复取消、开始时间边界、故障回滚和对账 SQL 验证使用 `run-day9.ps1`。运行步骤、111 项测试结果和交付清单见 [Day 9 免费预约验收](../../qa/guides/day9-concurrency.md)。下文为 Day 8 票务并发脚本。

在项目根目录使用 PowerShell。确认本地 MySQL 已启动，Java 使用项目配置的 JDK，Maven 可以运行。测试沿用 `src/main/resources/application.yml` 的数据库连接配置。

## 设置本次进程的数据库凭据

使用现有的 `DB_PASSWORD`，或通过隐藏输入设置它：

```powershell
	$env:DB_PASSWORD = [System.Net.NetworkCredential]::new(
	    '', (Read-Host 'MySQL password' -AsSecureString)
	).Password
```

缺少凭据时，脚本直接失败。单独执行 Maven 时，MySQL 测试可能被跳过；跳过不能作为通过并发验证的证据。

## 执行 20 和 50 并发

```powershell
	powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tests\concurrency\run-day8.ps1
```

该命令只为本次 PowerShell 进程设置脚本执行策略。脚本启动随机 HTTP 端口的测试应用，创建独立随机 MySQL schema，完成后关闭应用并删除自己创建的库。

如果 `mvn` 不在 PATH 中，传入本机 Maven 路径。当前工作区可使用 IDEA 的 Maven：

```powershell
	powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tests\concurrency\run-day8.ps1 `
	    -MavenCommand 'D:\Computer_Science\IntelliJ IDEA 2026.2.1\plugins\maven-plugin\lib\maven3\bin\mvn.cmd' `
	    -MavenRepository "$env:USERPROFILE\.m2\repository"
```

只执行其中一个并发级别时，增加 `-Concurrency 20` 或 `-Concurrency 50`。脚本同时执行关闭、AT-08、死锁和重试回归；选择单个级别只减少并发组合，不省略这些回归。

需要直接使用 Maven 时，执行以下选择器：

```powershell
	mvn '-Dtest=TicketOrderSmallConcurrencyMySqlIntegrationTest,TicketOrderClosingMySqlIntegrationTest,PaymentRetryMySqlIntegrationTest,PaymentRetryExecutorTest' '-Dday8.concurrency.levels=20,50' test
```

## 核对测试结果

默认 20、50 两个级别应得到 `Tests run: 72, Failures: 0, Errors: 0, Skipped: 0`，以及 `BUILD SUCCESS`。选择单个级别时是 69 项。出现测试失败时保留 Maven 输出和 `target/surefire-reports`，先查看失败的断言。

查看每组竞争的原始记录：

```powershell
	Get-ChildItem .\target\verification\day8-concurrency-*.json | ForEach-Object {
	    $result = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
	    [pscustomobject]@{
	        scenario = $result.observation.scenario
	        participants = $result.observation.participants
	        databaseWaiters = $result.observation.observedSimultaneousDatabaseWaiters
	        finalState = $result.observation.finalState.status
	        reconciliation = $result.observation.paymentInvariants
	    }
	} | Format-Table -AutoSize
```

按 `participants` 确认本次选择的并发级别。`outcomes` 中包含各操作的 HTTP 状态或关闭结果；`finalState` 中包含 Ticket、SOLD Seat、库存和限购计数。确认每组 `paymentInvariants` 为 `PASS`。

支付与取消的竞争中，失败方返回 `409 ORDER_NOT_PENDING` 属于预期结果。按最终数据库状态和资源断言判断通过，不能要求所有请求返回 200。支付重放多次返回 200 时，还需确认它们返回同一张 Ticket。

当前测试会先人为持有订单锁，让竞争者到齐后再释放，因此记录的耗时不适合作为吞吐、延迟或连接池容量结论。并发协调方式和完整清单见[Day 8 并发说明](../../qa/guides/day8-concurrency.md)。
