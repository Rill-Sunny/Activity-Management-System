package com.limitactivitysystem.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Best-effort short-lived duplicate suppression. Database idempotency remains authoritative. */
@Service
public class RedisRequestDeduplicator {
    private static final Logger LOG = LoggerFactory.getLogger(RedisRequestDeduplicator.class);
    private static final Duration LEASE = Duration.ofSeconds(10);
    private static final DefaultRedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final CacheMetrics metrics;
    @Value("${cache.redis.key-prefix:}")
    private String keyPrefix = "";

    public RedisRequestDeduplicator(StringRedisTemplate redis, CacheMetrics metrics) {
        this.redis = redis;
        this.metrics = metrics;
    }

    public Lease beginPayment(Long orderId, Long requesterId) {
        String key = keyPrefix + "las:dedupe:pay:" + orderId + ":" + requesterId;
        String owner = UUID.randomUUID().toString();
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, owner, LEASE);
            if (!Boolean.TRUE.equals(acquired)) metrics.dedupeRejected();
            return Boolean.TRUE.equals(acquired) ? new Lease(key, owner, false) : new Lease(key, owner, true);
        } catch (DataAccessException unavailable) {
            LOG.debug("Redis payment de-duplication unavailable orderId={} cause={}", orderId,
                    unavailable.getClass().getSimpleName());
            return new Lease(null, null, false);
        }
    }

    public final class Lease implements AutoCloseable {
        private final String key;
        private final String owner;
        private final boolean inProgress;

        private Lease(String key, String owner, boolean inProgress) {
            this.key = key;
            this.owner = owner;
            this.inProgress = inProgress;
        }

        public boolean inProgress() {
            return inProgress;
        }

        @Override
        public void close() {
            if (key == null || inProgress) return;
            try {
                redis.execute(RELEASE_IF_OWNER, List.of(key), owner);
            } catch (DataAccessException unavailable) {
                LOG.debug("Redis payment de-duplication release deferred key={} cause={}", key,
                        unavailable.getClass().getSimpleName());
            }
        }
    }
}
