package com.limitactivitysystem.outbox;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class OutboxTraceSupport {
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override public Iterable<String> keys(Map<String, String> carrier) { return carrier.keySet(); }
        @Override public String get(Map<String, String> carrier, String key) { return carrier.get(key); }
    };

    private final Tracer tracer;

    public OutboxTraceSupport(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("com.limitactivitysystem.outbox");
    }

    public Span startPublishSpan(OutboxEvent event, String routingKey) {
        Context parent = event.getTraceparent() == null || event.getTraceparent().isBlank()
                ? Context.root()
                : W3CTraceContextPropagator.getInstance().extract(Context.root(),
                    Map.of("traceparent", event.getTraceparent()), GETTER);
        return tracer.spanBuilder("outbox.publish")
                .setParent(parent)
                .setSpanKind(SpanKind.PRODUCER)
                .startSpan()
                .setAttribute("messaging.system", "rabbitmq")
                .setAttribute("messaging.destination.name", routingKey)
                .setAttribute("messaging.message.id", event.getEventId());
    }
}
