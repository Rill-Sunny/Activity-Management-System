package com.limitactivitysystem.observability;

import io.opentelemetry.api.trace.Span;
import org.slf4j.MDC;
import java.util.Map;

/** Restores the caller's MDC even on listener thread reuse and failures. */
public final class TraceLogContext implements AutoCloseable {
    private final Map<String, String> previous = MDC.getCopyOfContextMap();
    public TraceLogContext(Span span, String operation) {
        MDC.put("businessOperation", operation);
        if (MDC.get("requestId") == null) { MDC.put("requestId", "-"); }
        if (span.getSpanContext().isValid()) {
            MDC.put("traceId", span.getSpanContext().getTraceId());
            MDC.put("spanId", span.getSpanContext().getSpanId());
        }
    }
    @Override public void close() {
        if (previous == null) { MDC.clear(); } else { MDC.setContextMap(previous); }
    }
}
