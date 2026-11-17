package com.limitactivitysystem.qualification.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Low-cardinality metrics and structured logs for ticket qualification linkage. */
@Component
public class QualificationTelemetry {
    private static final Logger log = LoggerFactory.getLogger(QualificationTelemetry.class);
    private final MeterRegistry meters;

    public QualificationTelemetry(MeterRegistry meters) { this.meters = meters; }

    public void eventReceived(String eventId) {
        increment("qualification.event.received");
        log.info("qualification.event.received eventId={} traceId={}", eventId, traceId());
    }

    public void eventSkipped(String eventId, Long orderItemId, String reason) {
        increment("qualification.event.skipped");
        log.info("qualification.event.skipped eventId={} orderItemId={} reason={} traceId={}",
                eventId, orderItemId, reason, traceId());
    }

    public void afterSaleCreated(String eventId, Long taskId, Long orderItemId) {
        increment("qualification.aftersale.created");
        log.info("qualification.aftersale.created eventId={} taskId={} orderItemId={} traceId={}",
                eventId, taskId, orderItemId, traceId());
    }

    public void afterSaleSucceeded(String eventId, Long taskId, Long orderItemId) {
        increment("qualification.aftersale.succeeded");
        log.info("qualification.aftersale.succeeded eventId={} taskId={} orderItemId={} traceId={}",
                eventId, taskId, orderItemId, traceId());
    }

    public void afterSaleFailed(String eventId, Long taskId, Long orderItemId, String reason) {
        increment("qualification.aftersale.failed");
        log.error("qualification.aftersale.failed eventId={} taskId={} orderItemId={} reason={} traceId={}",
                eventId, taskId, orderItemId, reason, traceId());
    }

    public void afterSaleRetry(String eventId, Long taskId, Long orderItemId, String previousFailure) {
        increment("qualification.aftersale.retry");
        log.warn("qualification.aftersale.retry eventId={} taskId={} orderItemId={} previousFailure={} traceId={}",
                eventId, taskId, orderItemId, previousFailure, traceId());
    }

    private void increment(String name) { meters.counter(name).increment(); }

    private String traceId() {
        var context = Span.current().getSpanContext();
        return context.isValid() ? context.getTraceId() : "-";
    }
}
