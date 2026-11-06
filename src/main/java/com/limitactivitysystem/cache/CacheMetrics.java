package com.limitactivitysystem.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Records cache outcomes with bounded cache-type tags. */
@Component
public class CacheMetrics {
    private final MeterRegistry registry;

    public CacheMetrics(MeterRegistry registry) {
        this.registry = registry;
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

    private String cacheType(String cacheIdentity) {
        if (cacheIdentity.startsWith("las:activity:")) return "activity";
        if (cacheIdentity.startsWith("las:seat-layout:")) return "seat-layout";
        if (cacheIdentity.startsWith("las:product:")) return "product";
        return "other";
    }
}
