package com.limitactivitysystem.cache;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisFailureFallbackTest {
    @Test
    void cacheAsideWritesAndReadsItsVersionedEnvelope() {
        RedisCacheStore store = mock(RedisCacheStore.class);
        CacheInvalidationRecorder generations = mock(CacheInvalidationRecorder.class);
        AtomicReference<String> value = new AtomicReference<>();
        when(store.read("las:activity:7")).thenAnswer(ignored -> value.get());
        when(generations.currentGeneration("las:activity:7")).thenReturn(0L);
        when(store.writeIfGenerationMatches(anyString(), anyLong(), anyString(), anyLong())).thenAnswer(call -> {
            value.set(call.getArgument(2));
            return true;
        });
        var cache = new CacheAsideService(store, generations, new com.fasterxml.jackson.databind.ObjectMapper(),
                new CacheMetrics(new SimpleMeterRegistry()));

        assertThat(cache.get("las:activity:7", String.class, () -> "public-detail")).isEqualTo("public-detail");
        assertThat(cache.get("las:activity:7", String.class, () -> "unexpected-origin-read")).isEqualTo("public-detail");
    }

    @Test
    void cacheReadFallsBackToTheOriginWhenRedisIsUnavailable() {
        RedisCacheStore store = mock(RedisCacheStore.class);
        CacheInvalidationRecorder generations = mock(CacheInvalidationRecorder.class);
        when(store.read("las:product:42")).thenThrow(new RedisConnectionFailureException("Redis stopped"));
        when(generations.currentGeneration("las:product:42")).thenReturn(0L);
        var cache = new CacheAsideService(store, generations, new com.fasterxml.jackson.databind.ObjectMapper(),
                new CacheMetrics(new SimpleMeterRegistry()));

        assertThat(cache.get("las:product:42", String.class, () -> "from-mysql")).isEqualTo("from-mysql");
        verify(store, never()).writeIfGenerationMatches(anyString(), anyLong(), anyString(), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void paymentDeduplicationFailsOpenWhenRedisIsUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenThrow(new RedisConnectionFailureException("Redis stopped"));
        var deduplicator = new RedisRequestDeduplicator(redis, new CacheMetrics(new SimpleMeterRegistry()));

        try (var lease = deduplicator.beginPayment(1001L, 9001L)) {
            assertThat(lease.inProgress()).isFalse();
        }
        verify(redis).opsForValue();
        verify(values).setIfAbsent(anyString(), anyString(), any(java.time.Duration.class));
        verifyNoMoreInteractions(redis, values);
    }

    @Test
    void cacheMissesBoundConcurrentOriginLoadsToThirtyTwo() throws Exception {
        RedisCacheStore store = mock(RedisCacheStore.class);
        CacheInvalidationRecorder generations = mock(CacheInvalidationRecorder.class);
        when(store.read(anyString())).thenReturn(null);
        when(generations.currentGeneration(anyString())).thenReturn(0L);
        when(store.writeIfGenerationMatches(anyString(), anyLong(), anyString(), anyLong())).thenReturn(true);
        var cache = new CacheAsideService(store, generations, new com.fasterxml.jackson.databind.ObjectMapper(),
                new CacheMetrics(new SimpleMeterRegistry()));

        List<String> identities = new ArrayList<>();
        Set<Integer> stripeIds = new HashSet<>();
        for (int candidate = 0; identities.size() < 40; candidate++) {
            String identity = "las:origin-test:" + candidate;
            if (stripeIds.add(Math.floorMod(identity.hashCode(), 256))) identities.add(identity);
        }
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch thirtyTwoLoadsEntered = new CountDownLatch(32);
        CountDownLatch allowLoadsToFinish = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(identities.size());
        try {
            List<Future<String>> results = identities.stream().map(identity -> executor.submit(() -> {
                start.await();
                return cache.get(identity, String.class, () -> {
                    int now = active.incrementAndGet();
                    maximum.accumulateAndGet(now, Math::max);
                    thirtyTwoLoadsEntered.countDown();
                    try {
                        if (!allowLoadsToFinish.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting to release origin loads");
                        }
                        return "from-mysql";
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    } finally {
                        active.decrementAndGet();
                    }
                });
            })).toList();
            start.countDown();
            assertThat(thirtyTwoLoadsEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(active.get()).isEqualTo(32);
            allowLoadsToFinish.countDown();
            for (Future<String> result : results) assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("from-mysql");
            assertThat(maximum.get()).isEqualTo(32);
        } finally {
            allowLoadsToFinish.countDown();
            executor.shutdownNow();
        }
    }
}
