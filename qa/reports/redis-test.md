# Redis 缓存测试记录

## 当前结果

2026-11-06 在工作区运行了缓存回源、指标和支付遥测 JUnit 单元测试。25 个用例全部通过。主源码和测试源码也通过 `javac` 编译。

MySQL/Redis 联合集成测试未执行。当前 PowerShell 没有 `docker` 或 `mvn` 命令，`DB_PASSWORD` 和 `REDIS_TEST_ENABLED` 未设置。TCP 3306 可连接，但没有测试数据库凭据。TCP 6379 不可连接，因此不能报告 Redis 健康、键状态、数据库前后状态或容器停机验收。

## 单元测试 fixture 与状态

这些用例使用 Mockito 提供 Redis、generation 表和 JDBC 任务行。它们不连接外部 Redis 或 MySQL。

| 测试 | 固定 fixture | 初始状态 | 最终断言 |
|---|---|---|---|
| `cacheAsideCountsMissFallbackAndHitWithOnlyCacheTypeTag` | `las:activity:7341` | Redis Key 不存在；MySQL 回源返回 `published-detail` | MISS、回源、HIT 各计数 1；`cache` 标签为 `activity` |
| `cacheOutageCountsDatabaseFallbackWithoutUsingTheIdentityAsATag` | `las:product:9274` | Redis 连接失败；回源返回 `mysql-product` | MISS 和 `cache.db_fallback` 各计数 1；标签只有 `cache=product` |
| `occupiedDedupeKeyIncrementsAnUntaggedCounter` | order `1001`、requester `9012` | `SET NX` 返回 Key 已存在 | 返回 `inProgress`；`redis.dedupe_rejected=1`，没有标签 |
| `failedInvalidationIncrementsTheBoundedCacheTypeCounter` | 任务 `55`、Key `las:product:8321`、generation `9` | Redis 删除失败 | `cache.invalidation_failure{cache=product}=1`，JDBC 更新重试元数据 |

单元测试不产生外部数据库或 Redis 状态变化。内存 `SimpleMeterRegistry` 在每个测试中创建，因此计数不会跨测试保留。

## 编译和已执行命令

本机 Maven 不可用，因此使用工作区中由 Maven 依赖生成的 `javac` 参数文件和 JUnit Platform Launcher 执行测试。`javac` 返回码为 0。JDK 23 在关闭本地 Maven 缓存中的 `jdom2-2.0.6.1.jar` 时打印 `AccessDeniedException`，但未改变编译返回码。

```powershell
javac @target/verify-main.args src/main/java/com/limitactivitysystem/cache/CacheMetrics.java
javac @target/verify-test.args src/test/java/com/limitactivitysystem/cache/CacheMetricsTest.java
```

测试运行器选择 `RedisFailureFallbackTest`、`CacheMetricsTest` 和 `PaymentTelemetryTest`。实际运行命令如下：

```powershell
$classpath = (Get-Content target/run-junit-exec.args)[1].Trim('"')
javac -encoding UTF-8 -cp $classpath -d target/verify-test-classes target/RunRedisTests.java
java -cp $classpath RunRedisTests
```

最终结果：25 tests found、25 started、25 successful、0 failed、0 skipped。JUnit 在 Mockito 测试中记录了动态 Java Agent 警告；测试结果不受影响。

## MySQL 与 Redis 联合集成用例

`ProductCatalogMySqlIntegrationTest` 使用固定业务场景和数据库生成的主键。缓存验收用例把实际 fixture ID、MySQL 查询前后值、Redis Key 状态和指标变化写入 `target/verification/day18-redis-*.json`。`@BeforeEach` 创建已发布付费活动和用户；具体商品用例创建公开商品并上架。测试结束清理该类创建的业务行。

集成用例覆盖活动首次 MISS 回源和第二次 HIT、空值短 TTL 到期、商品下架提交后的失效、商品更新回滚后不缓存未提交名称、旧值并发回填、12 路同 Key 去重竞争、过期旧令牌不能删除新令牌、Redis 访问失败时从真实 MySQL 回源，以及失效任务失败后的重试。Redis 相关方法要求 `REDIS_TEST_ENABLED=true`；整个测试类还要求 `DB_PASSWORD`。

以下状态没有在本次执行中观测到：Redis 容器健康状态、Redis 键前后值、测试库行前后值和 `docker compose stop redis` 后的交易结果。不能把这些条件用例计作通过。

| 验收场景 | 集成测试方法 | 本次状态 |
|---|---|---|
| 活动首次 MISS 回源、第二次 HIT | `publishedActivityCacheMissLoadsMysqlAndNextReadHitsRedis` | 未运行，需 Redis 和测试库 |
| 空值 TTL 到期 | `missingActivityUsesShortNegativeCacheAndReloadsAfterExpiry` | 未运行，需 Redis 和测试库 |
| Product 提交后旧缓存失效 | `productOffSaleCommitInvalidatesItsPreviouslyCachedPublicDetail` | 未运行，需 Redis 和测试库 |
| Product 回滚不缓存未提交名称 | `productRollbackNeverCachesTheUncommittedName` | 未运行，需 Redis 和测试库 |
| 并发旧值回填 | `cacheGenerationPreventsOldMissValueFromBeingRefilledAfterCommit` | 未运行，需 Redis 和测试库 |
| Redis 访问失败时从 MySQL 回源 | `productReadFallsBackToRealMysqlWhenRedisAccessFails` | 未运行，需 Redis 和测试库；用 spy 注入 Redis 连接异常 |
| 同 Key 去重竞争 | `paymentDedupeCompetitionAllowsOneLiveOwner` | 未运行，需 Redis 和测试库 |
| 令牌过期和所有者校验释放 | `expiredDedupeOwnerCannotReleaseTheNextOwnersKey` | 未运行，需 Redis 和测试库 |
| Redis 停机后数据库支付仍幂等 | `p18_duplicateConcurrentPaymentReturnsTheSamePaidOrderWithoutRepeatingEffects` | 未运行，需停 Redis 和测试库 |
| Redis 停机后重复退款不重复恢复资源 | `unshippedPartialRefundRestoresSkuQuotaAndReturnsTheOriginalIdempotentResult` | 未运行，需停 Redis 和测试库 |
| 失效失败持久化并重试 | `failedCacheInvalidationStaysDurableAndScannerRetriesIt` | 未运行，需 Redis 和测试库 |

## 集成测试执行命令

在具有 Docker Compose、Maven、MySQL 测试库和 Redis 的 PowerShell 环境中执行。向环境变量提供测试用数据库密码，不要把密码提交到仓库。

```powershell
$env:MYSQL_APP_PASSWORD = '<test-app-password>'
$env:MYSQL_ROOT_PASSWORD = '<test-root-password>'
$env:DB_PASSWORD = $env:MYSQL_ROOT_PASSWORD
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://localhost:3307/limit_activity_system?serverTimezone=Asia/Shanghai'
$env:SPRING_DATASOURCE_USERNAME = 'root'
$env:REDIS_HOST = 'localhost'
$env:REDIS_TEST_ENABLED = 'true'
docker compose -f deploy/compose.yaml up -d mysql redis
docker compose -f deploy/compose.yaml ps mysql redis
mvn '-Dtest=ProductCatalogMySqlIntegrationTest' test
docker compose -f deploy/compose.yaml stop redis
$env:REDIS_TEST_ENABLED = 'false'
mvn '-Dtest=ProductCatalogMySqlIntegrationTest#p18_duplicateConcurrentPaymentReturnsTheSamePaidOrderWithoutRepeatingEffects' test
mvn '-Dtest=ProductCatalogMySqlIntegrationTest#unshippedPartialRefundRestoresSkuQuotaAndReturnsTheOriginalIdempotentResult' test
docker compose -f deploy/compose.yaml start redis
docker compose -f deploy/compose.yaml ps mysql redis
$env:REDIS_TEST_ENABLED = 'true'
mvn '-Dtest=ProductCatalogMySqlIntegrationTest#failedCacheInvalidationStaysDurableAndScannerRetriesIt' test
```

Compose 要求 `MYSQL_APP_PASSWORD` 与 `MYSQL_ROOT_PASSWORD`。集成测试为每次运行创建并删除随机测试 Schema，因此使用拥有建库权限的 Compose root 账号。Compose 把 MySQL 发布到 `localhost:3307`；`SPRING_DATASOURCE_URL` 覆盖应用默认的 `localhost:3306` 地址。停机步骤只运行单个数据库支付幂等用例，恢复 Redis 后再运行失效任务补偿用例。
