package com.limitactivitysystem.observability;

import com.limitactivitysystem.cancellation.CancellationMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.util.Map;

/** A recovery span keeps its scheduler parent, if present, and links to the durable source event. */
@Component
public class TaskTraceLinks {
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    public TaskTraceLinks(DataSource dataSource, MeterRegistry meters) {
        jdbc = new JdbcTemplate(dataSource); jdbc.setQueryTimeout(2); this.meters = meters;
    }
    public SpanContext find(String operation, Object[] args) {
        String sql;
        Object id;
        if (operation.equals("qualification.task.process") && args[0] instanceof Long taskId) {
            sql = "SELECT e.traceparent FROM ticket_qualification_after_sale_task t JOIN outbox_event e ON e.event_id=t.source_event_id WHERE t.task_id=?";
            id = taskId;
        } else if (operation.equals("cancellation.item.process") && args[0] instanceof CancellationMapper.Item item) {
            sql = "SELECT e.traceparent FROM cancellation_batch b JOIN outbox_event e ON e.aggregate_id=b.event_id AND e.event_type IN ('SESSION_CANCELLED','ACTIVITY_CANCELLED') WHERE b.batch_id=? ORDER BY e.created_at LIMIT 1";
            id = item.batchId();
        } else { return SpanContext.getInvalid(); }
        try {
            var contexts = jdbc.queryForList(sql, String.class, id);
            if (contexts.isEmpty() || contexts.getFirst() == null) { return SpanContext.getInvalid(); }
            return Span.fromContext(W3CTraceContextPropagator.getInstance().extract(Context.root(),
                    Map.of("traceparent", contexts.getFirst()), new TextMapGetter<Map<String, String>>() {
                        public Iterable<String> keys(Map<String, String> values) { return values.keySet(); }
                        public String get(Map<String, String> values, String key) { return values.get(key); }
                    })).getSpanContext();
        } catch (org.springframework.dao.DataAccessException unavailable) {
            // Observability must not prevent business recovery. The task ID still permits investigation.
            meters.counter("observability.trace.link.failure").increment();
            return SpanContext.getInvalid();
        }
    }
}
