package com.limitactivitysystem.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CacheMetricsTest {
    @Test
    void cacheAsideCountsMissFallbackAndHitWithOnlyCacheTypeTag() {
        String identity = "las:activity:7341";
        var registry = new SimpleMeterRegistry();
        var cacheStore = mock(RedisCacheStore.class);
        var generations = mock(CacheInvalidationRecorder.class);
        AtomicReference<String> entry = new AtomicReference<>();
        when(cacheStore.read(identity)).thenAnswer(ignored -> entry.get());
        when(generations.currentGeneration(identity)).thenReturn(0L);
        when(cacheStore.writeIfGenerationMatches(anyString(), any(Long.class), anyString(), any(Long.class)))
                .thenAnswer(invocation -> {
                    entry.set(invocation.getArgument(2));
                    return true;
                });
        var cache = new CacheAsideService(cacheStore, generations, new ObjectMapper(), new CacheMetrics(registry));

        assertThat(cache.get(identity, String.class, () -> "published-detail")).isEqualTo("published-detail");
        assertThat(cache.get(identity, String.class, () -> "must-not-load")).isEqualTo("published-detail");

        assertThat(registry.get("cache.miss").tag("cache", "activity").counter().count()).isEqualTo(1);
        assertThat(registry.get("cache.db_fallback").tag("cache", "activity").counter().count()).isEqualTo(1);
        assertThat(registry.get("cache.hit").tag("cache", "activity").counter().count()).isEqualTo(1);
        assertThat(registry.get("cache.hit").counter().getId().getTags())
                .containsExactly(Tag.of("cache", "activity"));
    }

    @Test
    void cacheOutageCountsDatabaseFallbackWithoutUsingTheIdentityAsATag() {
        String identity = "las:product:9274";
        var registry = new SimpleMeterRegistry();
        var cacheStore = mock(RedisCacheStore.class);
        var generations = mock(CacheInvalidationRecorder.class);
        when(cacheStore.read(identity)).thenThrow(new RedisConnectionFailureException("Redis stopped"));
        when(generations.currentGeneration(identity)).thenReturn(0L);
        var cache = new CacheAsideService(cacheStore, generations, new ObjectMapper(), new CacheMetrics(registry));

        assertThat(cache.get(identity, String.class, () -> "mysql-product")).isEqualTo("mysql-product");

        assertThat(registry.get("cache.miss").tag("cache", "product").counter().count()).isEqualTo(1);
        assertThat(registry.get("cache.db_fallback").tag("cache", "product").counter().count()).isEqualTo(1);
        assertThat(registry.get("cache.db_fallback").counter().getId().getTags())
                .containsExactly(Tag.of("cache", "product"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void occupiedDedupeKeyIncrementsAnUntaggedCounter() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        var registry = new SimpleMeterRegistry();
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(false);
        var deduplicator = new RedisRequestDeduplicator(redis, new CacheMetrics(registry));

        try (var lease = deduplicator.beginPayment(1001L, 9012L)) {
            assertThat(lease.inProgress()).isTrue();
        }

        assertThat(registry.get("redis.dedupe_rejected").counter().count()).isEqualTo(1);
        assertThat(registry.get("redis.dedupe_rejected").counter().getId().getTags()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedInvalidationIncrementsTheBoundedCacheTypeCounter() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RedisCacheStore redis = mock(RedisCacheStore.class);
        var registry = new SimpleMeterRegistry();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet row = mock(ResultSet.class);
            when(row.getLong("task_id")).thenReturn(55L);
            when(row.getString("cache_identity")).thenReturn("las:product:8321");
            when(row.getLong("generation")).thenReturn(9L);
            return List.of(mapper.mapRow(row, 0));
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        org.mockito.Mockito.doThrow(new RedisSystemException("Redis stopped", new IllegalStateException("offline")))
                .when(redis).invalidate("las:product:8321", 9L);
        var delivery = new CacheInvalidationDelivery(jdbc, redis, new CacheMetrics(registry), 20);

        delivery.retryPending();

        assertThat(registry.get("cache.invalidation_failure").tag("cache", "product").counter().count()).isEqualTo(1);
        assertThat(registry.get("cache.invalidation_failure").counter().getId().getTags())
                .containsExactly(Tag.of("cache", "product"));
        verify(redis).invalidate("las:product:8321", 9L);
    }
}
