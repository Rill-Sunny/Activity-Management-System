# 缓存与短期去重

## 连接

宿主机上的 Spring Boot 使用 `localhost:6379`。运行在 Compose 网络内的应用容器将 `REDIS_HOST` 设置为 `redis`。Redis 连接超时为 300 毫秒，命令超时为 500 毫秒。Compose 每 2 秒运行一次 Redis `PING` 健康检查。

## 公开缓存

公开读取使用 Cache-Aside。`CacheAsideService` 先读 Redis；命中时反序列化并返回。未命中或 Redis 不可用时，读取 MySQL，再尝试回填 Redis。

| Redis Key | 缓存内容 | TTL |
|---|---|---:|
| `las:activity:{id}` | 已发布活动详情 | 30–35 秒 |
| `las:seat-layout:{sessionId}` | 场次分区信息和静态座位位置 | 30–35 秒 |
| `las:product:{id}` | 商品公开信息及展示库存快照 | 30–35 秒 |
| 上述对象的空结果 | 空值标记 | 3–5 秒 |

正缓存 TTL 从 MySQL 加载开始计时。Redis 使用绝对过期时间写入，因此线程暂停后恢复不会重新获得完整 TTL。单个进程最多同时执行 32 个缓存回源加载；同一条缓存 Key 通过本地分段锁合并并发未命中。

缓存仅用于公开展示。座位布局不包含座位可售状态。商品库存只作为展示快照。下单、支付、退款、资格、限购、订单状态和实际库存均由 MySQL 事务内的业务逻辑判定。公开缓存不保存用户专属资格结果。

## 版本和失效

每个公开缓存身份在 `cache_generation` 中有 MySQL 版本。缓存读取命中时，Redis Lua 脚本比较 Redis 版本与缓存条目版本。回源加载开始和结束时读取 MySQL 版本；写入时，另一个 Lua 脚本原子比较 Redis 版本，并拒绝已过期版本的回填。

业务事务通过 `CacheInvalidationRecorder` 递增 MySQL 版本并写入 `cache_invalidation_task`。`CacheInvalidationListener` 在 `AFTER_COMMIT` 阶段立即推进 Redis 版本并删除缓存 Key。`CacheInvalidationDelivery` 每 5 秒扫描未完成任务，失败任务保留并更新重试时间。

MySQL 与 Redis 之间没有分布式提交。若进程在数据库提交后、Redis 删除前退出，旧值最多保留至成功补偿或原缓存 TTL 到期。正缓存的最长 TTL 是 35 秒。交易判断不使用缓存值。

已接入的失效路径包括活动发布、场次及座位布局配置，以及商品 SKU 编辑、上架和下架。当前活动公开字段没有通用编辑入口；未来新增可变更字段时，必须在相同业务事务中调用 `CacheInvalidationRecorder.record`。

## Micrometer 指标

每个指标按一次逻辑请求或一次失效结果计数。缓存内部为了并发合并执行的第二次探测不重复计数。

| 指标 | 计数时机 | 标签 |
|---|---|---|
| `cache.hit` | 返回 Redis 中的正值或空值标记 | `cache=activity`, `product`, `seat-layout`, 或 `other` |
| `cache.miss` | 请求没有可用缓存并读取 MySQL | 同上 |
| `cache.db_fallback` | 缓存未命中或 Redis 不可用，回源读取 MySQL | 同上 |
| `cache.invalidation_failure` | Redis 失效操作失败，任务待重试 | 同上 |
| `redis.dedupe_rejected` | Redis Key 已存在，请求被短期抑制 | 无标签 |

指标不使用 `userId`、`productId`、`orderId` 或缓存 Key 作为标签。Actuator 的 `metrics` 端点已开放，可按指标名称查询计数。

## 支付短期去重

`RedisRequestDeduplicator` 对 `las:dedupe:pay:{orderId}:{requesterId}` 执行 `SET NX EX 10`，Value 是本次请求的随机所有者令牌。Key 已存在时，支付接口返回 `REQUEST_IN_PROGRESS` 或商品支付对应错误码。调用方可查询原订单状态。

释放 Key 时，Lua 脚本只有在 Redis 中的令牌仍属于当前请求时才删除 Key。令牌过期后，旧请求不能删除新请求创建的 Key。Redis 不可用时去重器放行请求；数据库幂等键、状态条件更新、事务和资源约束仍负责交易正确性。

## 验收入口

| 行为 | 测试入口 |
|---|---|
| 指标标签不含业务 ID | `CacheMetricsTest` |
| Redis 故障时缓存回源及并发上限 | `RedisFailureFallbackTest` |
| MySQL、Redis、TTL、失效和并发去重 | `ProductCatalogMySqlIntegrationTest` 中标记 `REDIS_TEST_ENABLED=true` 的用例 |

运行方式和当前验收结果见 [Redis 测试记录](../qa/reports/redis-test.md)。
