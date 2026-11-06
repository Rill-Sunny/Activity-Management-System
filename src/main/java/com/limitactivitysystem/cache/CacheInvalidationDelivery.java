package com.limitactivitysystem.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CacheInvalidationDelivery {
    private static final Logger LOG = LoggerFactory.getLogger(CacheInvalidationDelivery.class);
    private final JdbcTemplate jdbc;
    private final RedisCacheStore redis;
    private final CacheMetrics metrics;
    private final int batchSize;

    public CacheInvalidationDelivery(JdbcTemplate jdbc, RedisCacheStore redis, CacheMetrics metrics,
                                     @Value("${cache.invalidation.batch-size:20}") int batchSize) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.metrics = metrics;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deliver(long taskId) {
        List<Invalidation> rows = jdbc.query("""
                SELECT task_id, cache_identity, generation
                FROM cache_invalidation_task
                WHERE task_id = ? AND completed_at IS NULL
                """, (rs, row) -> new Invalidation(rs.getLong("task_id"), rs.getString("cache_identity"),
                rs.getLong("generation")), taskId);
        if (!rows.isEmpty()) deliver(rows.get(0));
    }

    @Scheduled(fixedDelayString = "${cache.invalidation.scan-delay-ms:5000}")
    public void retryPending() {
        List<Invalidation> pending = jdbc.query("""
                SELECT task_id, cache_identity, generation
                FROM cache_invalidation_task
                WHERE completed_at IS NULL AND next_attempt_at <= CURRENT_TIMESTAMP(3)
                ORDER BY task_id
                LIMIT ?
                """, (rs, row) -> new Invalidation(rs.getLong("task_id"), rs.getString("cache_identity"),
                rs.getLong("generation")), batchSize);
        pending.forEach(this::deliver);
    }

    private void deliver(Invalidation task) {
        try {
            redis.invalidate(task.cacheIdentity(), task.generation());
        } catch (DataAccessException failure) {
            metrics.invalidationFailure(task.cacheIdentity());
            LOG.warn("Cache invalidation remains pending taskId={} cause={}", task.taskId(),
                    failure.getClass().getSimpleName());
            recordRetry(task, failure);
            return;
        }
        try {
            jdbc.update("""
                    UPDATE cache_invalidation_task
                    SET completed_at = CURRENT_TIMESTAMP(3), last_error = NULL
                    WHERE task_id = ? AND completed_at IS NULL
                    """, task.taskId());
        } catch (DataAccessException failure) {
            LOG.warn("Cache invalidation applied but task acknowledgement failed taskId={} cause={}", task.taskId(),
                    failure.getClass().getSimpleName());
        }
    }

    private void recordRetry(Invalidation task, DataAccessException failure) {
        try {
            jdbc.update("""
                    UPDATE cache_invalidation_task
                    SET attempts = attempts + 1,
                        next_attempt_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 5 SECOND),
                        last_error = ?
                    WHERE task_id = ? AND completed_at IS NULL
                    """, failure.getClass().getSimpleName(), task.taskId());
        } catch (DataAccessException ignored) {
            LOG.warn("Could not update retry metadata for cache invalidation taskId={}", task.taskId());
        }
    }

    private record Invalidation(long taskId, String cacheIdentity, long generation) { }
}
