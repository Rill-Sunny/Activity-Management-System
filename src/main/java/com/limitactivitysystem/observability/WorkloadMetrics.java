package com.limitactivitysystem.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Scrapes only read immutable memory. A separate bounded scheduler refreshes shared-database state. */
@Component
@ConditionalOnProperty(name = "observability.workload.enabled", havingValue = "true")
public class WorkloadMetrics {
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private volatile Snapshot snapshot = new Snapshot(Double.NaN, Double.NaN, Map.of(), Map.of(), 0);

    public WorkloadMetrics(DataSource source, MeterRegistry meters) {
        jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(2); this.meters = meters;
        Gauge.builder("outbox.pending", this, s -> s.snapshot.pending()).register(meters);
        Gauge.builder("outbox.oldest.age", this, s -> s.oldestAge()).baseUnit("seconds").register(meters);
        Gauge.builder("observability.workload.snapshot.age", this, s -> s.snapshot.sampledAt() == 0 ? Double.NaN
                : Instant.now().getEpochSecond() - s.snapshot.sampledAt()).baseUnit("seconds").register(meters);
        for (String status : new String[]{"PENDING", "PROCESSING", "COMPLETED", "PARTIAL_FAILED"}) {
            Gauge.builder("cancellation.batch", this, s -> s.snapshot.batches().getOrDefault(status, Double.NaN))
                    .tag("status", status).register(meters);
        }
        for (String status : new String[]{"PENDING", "PROCESSING", "RETRY_PENDING", "FAILED", "SUCCEEDED"}) {
            Gauge.builder("cancellation.item", this, s -> s.snapshot.items().getOrDefault(status, Double.NaN))
                    .tag("status", status).register(meters);
        }
    }

    @Scheduled(fixedDelayString = "${observability.workload.refresh-ms:15000}", initialDelayString = "${observability.workload.initial-delay-ms:15000}", scheduler = "observabilityScheduler")
    public void refresh() {
        try {
            // FAILED remains unresolved until explicit replay; do not hide terminally failed events from backlog age.
            var outbox = jdbc.queryForMap("SELECT COUNT(*) AS pending, COALESCE(MAX(TIMESTAMPDIFF(MICROSECOND,created_at,CURRENT_TIMESTAMP(3)))/1000000.0,0) AS age FROM outbox_event WHERE status IN ('PENDING','CLAIMED','FAILED')");
            var batches = counts("SELECT status,COUNT(*) AS total FROM cancellation_batch GROUP BY status",
                    "PENDING", "PROCESSING", "COMPLETED", "PARTIAL_FAILED");
            var items = counts("SELECT status,COUNT(*) AS total FROM cancellation_batch_item GROUP BY status",
                    "PENDING", "PROCESSING", "RETRY_PENDING", "FAILED", "SUCCEEDED");
            snapshot = new Snapshot(((Number) outbox.get("pending")).doubleValue(),
                    Math.max(0, ((Number) outbox.get("age")).doubleValue()), batches, items, Instant.now().getEpochSecond());
        } catch (org.springframework.dao.DataAccessException unavailable) {
            meters.counter("observability.workload.refresh.failure").increment();
            // Keep the last sample and expose its age. Never report a false empty backlog on database failure.
        }
    }

    private double oldestAge() {
        Snapshot value = snapshot;
        return value.pending() > 0 ? value.oldestAge() + Math.max(0, Instant.now().getEpochSecond() - value.sampledAt()) : value.oldestAge();
    }
    private Map<String, Double> counts(String sql, String... statuses) {
        var values = new HashMap<String, Double>();
        for (String status : statuses) { values.put(status, 0.0); }
        jdbc.query(sql, row -> { values.computeIfPresent(row.getString("status"), (key, old) -> {
            try { return row.getDouble("total"); } catch (java.sql.SQLException exception) { throw new IllegalStateException(exception); }
        }); });
        return Map.copyOf(values);
    }
    private record Snapshot(double pending, double oldestAge, Map<String, Double> batches, Map<String, Double> items, long sampledAt) { }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "observability.workload.enabled", havingValue = "true")
    static class Scheduler {
        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name = "taskScheduler")
        ThreadPoolTaskScheduler taskScheduler() {
            var scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("business-scheduler-");
            scheduler.setRemoveOnCancelPolicy(true); return scheduler;
        }

        @Bean
        ThreadPoolTaskScheduler observabilityScheduler() {
            var scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("observability-");
            scheduler.setRemoveOnCancelPolicy(true); return scheduler;
        }
    }
}
