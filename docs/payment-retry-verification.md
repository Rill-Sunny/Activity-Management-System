# 订单时间边界与事务外重试

支付重试必须重新执行完整事务。第一次尝试可能已经占用限购、扣库存、卖出 Seat 并插入 Ticket；只重试最后一条 SQL 会沿用失败事务的中间状态。

InnoDB 检测死锁后会回滚选中的整个事务。锁等待超时默认只回滚等待的语句；本项目让异常退出 Spring 支付事务代理，由 Spring 回滚这次支付的全部写入，再决定是否重试。两种失败都不能在原支付方法内部吞掉后继续执行。依据：[MySQL 8.4 错误处理](https://dev.mysql.com/doc/refman/8.4/en/innodb-error-handling.html)。

## AT-08 的两个时间窗口

活动 `close_time` 控制创建新单，订单 `expires_at` 控制该订单的支付期限。[TicketOrderClosingMySqlIntegrationTest](../src/test/java/com/limitactivitysystem/activity/integration/TicketOrderClosingMySqlIntegrationTest.java) 在独立测试库中固定连接的 MySQL `timestamp`，让普通查询和最终条件 UPDATE 都使用同一个毫秒时间。事务结束后恢复连接的正常时钟，生产代码没有测试时钟开关。

| 创建时间 | 活动停售 | 订单到期 | 支付数据库时间 | 结果 |
|---|---|---|---|---|
| 19:58:00.000 | 20:00:00.000 | 20:13:00.000 | 20:05:00.000 | PAID |
| 19:58:00.000 | 20:00:00.000 | 20:13:00.000 | 20:12:59.999 | PAID |
| 19:58:00.000 | 20:00:00.000 | 20:13:00.000 | 20:13:00.000 | ORDER_EXPIRED，资源不变 |

场次取消，或场次恰在 20:05 开始时，20:05 的支付仍返回 `SESSION_NOT_AVAILABLE`。订单期限未到不代表场次仍允许交易。过期关闭也验证同一毫秒边界：20:12:59.999 不关闭，20:13:00.000 才能关闭为 `EXPIRED`。

## 反向锁序为什么形成等待环

[PaymentRetryMySqlIntegrationTest](../src/test/java/com/limitactivitysystem/activity/integration/PaymentRetryMySqlIntegrationTest.java) 让两个真实连接各自先持有一条订单锁，随后同时请求另一条订单锁。测试直接调用 Mapper，不让支付重试层掩盖这次人工死锁。

| 事务 A | 事务 B |
|---|---|
| 锁较小 Order ID | 锁较大 Order ID |
| 请求较大 Order ID，等待 B | 请求较小 Order ID，等待 A |

MySQL 选择其中一个事务回滚，另一个继续提交。实际异常为 `1213 / 40001`。证据包含连接 ID、两笔订单 ID、逐条 SQL、锁获取顺序、Spring 事务完成状态和完整异常栈；另存 `SHOW ENGINE INNODB STATUS` 中属于本次测试库的最新死锁段。

对照实验让两笔事务都先锁较小 ID，再锁较大 ID。第二个事务在第一条订单上等待；第一个事务不再反向等待它，能够提交并释放锁。测试通过 `performance_schema.data_lock_waits` 确认真实等待后放行，最终两笔事务都提交。

这个对照消除了本次双订单等待环。项目仍需要在所有写路径维护统一锁协议，并在数据库报告可恢复的并发错误时有限重试。[MySQL 的死锁处理建议](https://dev.mysql.com/doc/refman/8.4/en/innodb-deadlocks-handling.html)同时要求一致的访问顺序与事务重试准备。

## 支付重试从事务外开始

调用关系为 `TicketPaymentService → PaymentRetryExecutor → TicketPaymentTransactionService.pay`。前两层挂起调用方事务；最后一层每次开启独立的 `REQUIRES_NEW / READ_COMMITTED` 事务。

重试白名单按 MySQL 错误码和 SQLState 判断：`1213 / 40001` 为死锁，`1205 / HY000` 或 `1205 / 40001` 为锁等待超时。本次 Connector/J 的实际日志点输出是 `1205 / 40001 / MySQLTransactionRollbackException`；不能只根据 SQLState 把它误标为死锁。

最多三次总尝试，只有两次退避，分别为 50–75ms 和 100–125ms。业务拒绝直接返回，包括库存不足、限购、白名单、座位冲突、过期和 CLOSED；其他 SQL、断连与未知异常同样不进入重试。

真实超时实验在 Ticket 已插入之后，使用同一支付连接请求另一笔被竞争事务锁住的订单。测试只把该连接的 `innodb_lock_wait_timeout` 临时设为 1 秒，完成后恢复原值，不修改服务器全局配置。

| 实验 | 事务完成顺序 | 最终结果 |
|---|---|---|
| 第一次超时，退避前释放竞争锁 | ROLLBACK → COMMIT | HTTP 200，恰好一张 Ticket，库存只扣一次，限购只加一次 |
| 三次都被同一竞争锁阻塞 | ROLLBACK → ROLLBACK → ROLLBACK | HTTP 503，订单与全部资源和初始快照一致 |

测试在每次退避入口确认当前没有活动事务，上一尝试已经完成回滚，且资源快照已恢复。每次尝试记录不同的 Spring 事务资源对象；连接池可能复用同一物理连接，连接 ID 不同不是“新事务”的必要条件。最终两种结果都执行项目统一的跨表支付对账 SQL。

三次耗尽返回 `PAYMENT_CONCURRENCY_RETRY_EXHAUSTED`，包含 `reason` 和 `attempts: 3`。日志记录实际 `retryCount`、`durationMs`、`reason` 和 `outcome`；`payment.retry{reason}` 统计各类原因实际引发的额外尝试，`payment.retry.duration` 记录出现并发冲突的请求耗时。既有支付成功、失败和总时长指标仍按一次用户请求统计。

## 可复现实验证据

以下 Maven 测试选择器重新执行时间边界、死锁、超时重试和规则白名单测试。MySQL 测试要求本机已有 `DB_PASSWORD`，使用随机独立 schema，结束后删除自己创建的库；无凭据时按既有测试约定跳过。

```powershell
	mvn '-Dtest=TicketOrderClosingMySqlIntegrationTest,PaymentRetryMySqlIntegrationTest,PaymentRetryExecutorTest' test
```

每次运行在 `target/verification` 生成 `at08-*.json`、`day8-deadlock.json`、`day8-deadlock-innodb.txt`、`day8-ordered-locks.json`、`day8-retry-success.json` 和 `day8-retry-exhausted.json`。本次执行的保存副本位于 [verification/day8](verification/day8)，包含实际异常、SQL、事务顺序、MySQL 错误码和重试日志。

本次相关回归共 311 项，全部通过且没有跳过。[run-summary.json](verification/day8/run-summary.json) 记录选定测试类的结果和十份原始证据的 SHA-256。

后续补充的 20/50 并发脚本、完整 Day 8 清单和新的运行结果见 [Day 8 小规模并发验证](day8-concurrency.md)。上面的 311 项记录保留为前次回归快照。
