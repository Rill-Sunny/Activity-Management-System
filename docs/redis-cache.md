# Redis cache and duplicate suppression

## Connection

`application.yml` defaults to `localhost:6379` for a Spring Boot process on the host. Set `REDIS_HOST=redis` when the application runs on the Compose network, where `redis` is the Compose service name. Redis readiness is exposed through the Compose `PING` health check. The Redis data volume enables AOF plus periodic RDB snapshots.

## Public cache entries

| Cache identity | Contents | Lifetime |
| --- | --- | --- |
| `las:activity:{id}` | Published activity detail | 30–35 seconds |
| `las:seat-layout:{sessionId}` | Zone labels and static seat positions | 30–35 seconds |
| `las:product:{id}` | Public product detail and display stock snapshot | 30–35 seconds |
| Missing public object | Negative cache marker | 3–5 seconds |

Seat allocation status is not part of the seat layout response. Product stock in the public detail is only a display snapshot. Order creation, payment, refund, qualification and inventory decisions continue to read transaction-protected MySQL state. User-specific qualification results are not put in these shared cache entries.

Cache misses are coalesced by a bounded set of local locks, with no more than 32 origin loads running per application process. A refill records the MySQL generation before loading and checks it again before writing. Redis Lua scripts compare the Redis generation atomically with a refill or invalidation, so a refill resumed after a committed invalidation is rejected. Entry expiry is anchored to the start of the origin load and written as an absolute Redis expiry, so a delayed fill cannot restart its TTL.

## Durable invalidation

The domain transaction increments `cache_generation` and inserts a `cache_invalidation_task` row. An `AFTER_COMMIT` listener immediately advances the Redis generation and deletes the entry. A scheduled scanner retries unfinished tasks; delivery is idempotent if the process crashes after the Redis command but before the database acknowledgement. This is a small database-backed retry queue and can be moved to the Day 19 Outbox/RabbitMQ flow later.

There is no cross-database atomic commit between MySQL and Redis. If a process dies after its MySQL commit but before Redis invalidation, an old entry can be served until the scanner repairs it or the entry expires. The maximum positive-entry TTL is 35 seconds; transactional purchase and payment decisions do not rely on these cached values.

## Payment duplicate suppression

Payment endpoints use `SET ... NX` with a unique owner token and a 10-second expiry. Keys include the order and authenticated buyer. A key already held returns `REQUEST_IN_PROGRESS` (or the product equivalent) so the buyer can query the order. Release uses a Lua compare-and-delete script. Redis errors fail open to the existing database idempotency, state transitions and resource constraints; the Redis key is never treated as proof that a payment succeeded.
