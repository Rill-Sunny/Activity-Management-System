package com.limitactivitysystem.cache;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;

/** Writes a durable invalidation intent in the same transaction as the domain change. */
@Service
public class CacheInvalidationRecorder {
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public CacheInvalidationRecorder(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String cacheIdentity) {
        jdbc.update("""
                INSERT INTO cache_generation(cache_identity, generation) VALUES (?, 1)
                ON DUPLICATE KEY UPDATE generation = generation + 1
                """, cacheIdentity);
        Long generation = jdbc.queryForObject(
                "SELECT generation FROM cache_generation WHERE cache_identity = ?", Long.class, cacheIdentity);
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO cache_invalidation_task(cache_identity, generation)
                    VALUES (?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, cacheIdentity);
            statement.setLong(2, generation);
            return statement;
        }, key);
        Number taskId = key.getKey();
        if (taskId == null) {
            throw new IllegalStateException("Cache invalidation task id was not generated");
        }
        events.publishEvent(new CacheInvalidationRecorded(taskId.longValue()));
    }

    public long currentGeneration(String cacheIdentity) {
        Long generation = jdbc.query("SELECT generation FROM cache_generation WHERE cache_identity = ?",
                result -> result.next() ? result.getLong(1) : null, cacheIdentity);
        return generation == null ? 0L : generation;
    }
}
