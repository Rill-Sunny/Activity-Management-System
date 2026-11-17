package com.limitactivitysystem.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** Cache-aside reads with bounded origin concurrency and a generation check before refill. */
@Service
public class CacheAsideService {
    private static final Logger LOG = LoggerFactory.getLogger(CacheAsideService.class);
    private static final int ORIGIN_CONCURRENCY = 32;
    private static final Object[] STRIPES = new Object[256];

    static {
        for (int i = 0; i < STRIPES.length; i++) STRIPES[i] = new Object();
    }

    private final RedisCacheStore cache;
    private final CacheInvalidationRecorder generations;
    private final ObjectMapper mapper;
    private final CacheMetrics metrics;
    private final Semaphore originSlots = new Semaphore(ORIGIN_CONCURRENCY, true);

    public CacheAsideService(RedisCacheStore cache, CacheInvalidationRecorder generations,
                             ObjectMapper mapper, CacheMetrics metrics) {
        this.cache = cache;
        this.generations = generations;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    public <T> T get(String cacheIdentity, Class<T> valueType, Supplier<T> loader) {
        // A transaction can have uncommitted generation changes. Bypass both cache reads and
        // fills until it completes so a rollback cannot publish transaction-local state.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            metrics.databaseFallback(cacheIdentity);
            return loader.get();
        }

        CacheRead<T> first = read(cacheIdentity, valueType);
        if (first.hit()) {
            metrics.hit(cacheIdentity);
            return first.value();
        }

        Object stripe = STRIPES[Math.floorMod(cacheIdentity.hashCode(), STRIPES.length)];
        synchronized (stripe) {
            boolean redisAvailable = first.available();
            if (redisAvailable) {
                CacheRead<T> second = read(cacheIdentity, valueType);
                if (second.hit()) {
                    metrics.hit(cacheIdentity);
                    return second.value();
                }
                redisAvailable = second.available();
            }

            metrics.miss(cacheIdentity);
            metrics.databaseFallback(cacheIdentity);
            try {
                originSlots.acquire();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for a cache origin slot", interrupted);
            }
            try {
                long fillStartedAt = System.currentTimeMillis();
                long observedGeneration = generations.currentGeneration(cacheIdentity);
                T value = loader.get();
                long currentGeneration = generations.currentGeneration(cacheIdentity);
                if (redisAvailable && currentGeneration == observedGeneration) {
                    try {
                        String serialized = mapper.writeValueAsString(new CacheEnvelope(currentGeneration, value != null, value));
                        Duration ttl = value == null
                                ? Duration.ofSeconds(3 + ThreadLocalRandom.current().nextInt(3))
                                : Duration.ofSeconds(30 + ThreadLocalRandom.current().nextInt(6));
                        cache.writeIfGenerationMatches(cacheIdentity, currentGeneration, serialized,
                                fillStartedAt + ttl.toMillis());
                    } catch (DataAccessException | JsonProcessingException failure) {
                        if (failure instanceof DataAccessException redisFailure) {
                            metrics.redisFailure(CacheMetrics.RedisOperation.FILL, redisFailure);
                        }
                        LOG.debug("Skipping Redis cache fill identity={} type={}", cacheIdentity,
                                failure.getClass().getSimpleName());
                    }
                }
                return value;
            } finally {
                originSlots.release();
            }
        }
    }

    private <T> CacheRead<T> read(String cacheIdentity, Class<T> valueType) {
        try {
            String serialized = cache.read(cacheIdentity);
            if (serialized == null) return CacheRead.miss();
            JsonNode envelope = mapper.readTree(serialized);
            if (!envelope.path("found").asBoolean(false)) return CacheRead.negativeHit();
            JsonNode value = envelope.get("value");
            if (value == null || value.isNull()) return CacheRead.miss();
            return CacheRead.hit(mapper.treeToValue(value, valueType));
        } catch (DataAccessException failure) {
            metrics.redisFailure(CacheMetrics.RedisOperation.READ, failure);
            LOG.debug("Redis cache read unavailable identity={} type={}", cacheIdentity,
                    failure.getClass().getSimpleName());
            return CacheRead.unavailable();
        } catch (JsonProcessingException | IllegalArgumentException malformed) {
            LOG.debug("Discarding malformed Redis cache entry identity={}", cacheIdentity);
            return CacheRead.miss();
        }
    }

    private record CacheEnvelope(long generation, boolean found, Object value) { }
    private record CacheRead<T>(boolean hit, T value, boolean available) {
        static <T> CacheRead<T> miss() { return new CacheRead<>(false, null, true); }
        static <T> CacheRead<T> unavailable() { return new CacheRead<>(false, null, false); }
        static <T> CacheRead<T> negativeHit() { return new CacheRead<>(true, null, true); }
        static <T> CacheRead<T> hit(T value) { return new CacheRead<>(true, value, true); }
    }
}
