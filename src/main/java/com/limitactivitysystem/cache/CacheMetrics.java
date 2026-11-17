package com.limitactivitysystem.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Records cache outcomes with bounded cache-type tags. */
@Component
public class CacheMetrics {
    private final MeterRegistry registry;

    public CacheMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (String cache : new String[]{"activity", "seat-layout", "product", "other"}) {
            for (String name : new String[]{"cache.hit", "cache.miss", "cache.db_fallback", "cache.invalidation_failure"}) {
                registry.counter(name, "cache", cache);
            }
        }
        registry.counter("redis.dedupe_rejected");
        for (RedisOperation operation : RedisOperation.values()) {
            for (String reason : new String[]{"TIMEOUT", "CONNECTION", "OTHER"}) {
                registry.counter("cache.redis_error", "operation", operation.name(), "reason", reason);
            }
        }
    }

    public void hit(String cacheIdentity) {
        registry.counter("cache.hit", "cache", cacheType(cacheIdentity)).increment();
    }

    public void miss(String cacheIdentity) {
        registry.counter("cache.miss", "cache", cacheType(cacheIdentity)).increment();
    }

    public void databaseFallback(String cacheIdentity) {
        registry.counter("cache.db_fallback", "cache", cacheType(cacheIdentity)).increment();
    }

    public void invalidationFailure(String cacheIdentity) {
        registry.counter("cache.invalidation_failure", "cache", cacheType(cacheIdentity)).increment();
    }

    public void dedupeRejected() {
        registry.counter("redis.dedupe_rejected").increment();
    }

    public enum RedisOperation { READ, FILL, INVALIDATE, DEDUPE_ACQUIRE, DEDUPE_RELEASE }

    /** Separate timeout evidence from ordinary cache misses; never label by key, user, or exception message. */
    public void redisFailure(RedisOperation operation, org.springframework.dao.DataAccessException failure) {
        String type = failure.getMostSpecificCause().getClass().getSimpleName();
        String reason = failure instanceof org.springframework.dao.QueryTimeoutException || type.contains("Timeout") ? "TIMEOUT"
                : failure instanceof org.springframework.data.redis.RedisConnectionFailureException || type.contains("Connect")
                || type.contains("ClosedChannel") ? "CONNECTION" : "OTHER";
        registry.counter("cache.redis_error", "operation", operation.name(), "reason", reason).increment();
    }

    private String cacheType(String cacheIdentity) {
        if (cacheIdentity.startsWith("las:activity:")) return "activity";
        if (cacheIdentity.startsWith("las:seat-layout:")) return "seat-layout";
        if (cacheIdentity.startsWith("las:product:")) return "product";
        return "other";
    }
}
