package com.limitactivitysystem.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class OutboxEventWriter {
    private final OutboxEventMapper events;
    private final ObjectMapper objectMapper;

    public OutboxEventWriter(OutboxEventMapper events, ObjectMapper objectMapper) {
        this.events = events;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String append(String eventType, Long aggregateId, Map<String, ?> eventPayload) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.putAll(eventPayload);
        String traceparent = currentTraceparent();
        try {
            events.insert(eventId, eventType, aggregateId, objectMapper.writeValueAsString(payload), traceparent);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize outbox event " + eventId, exception);
        }
        return eventId;
    }

    private String currentTraceparent() {
        SpanContext context = Span.current().getSpanContext();
        if (!context.isValid()) { return null; }
        return "00-" + context.getTraceId() + "-" + context.getSpanId()
                + (context.getTraceFlags().isSampled() ? "-01" : "-00");
    }
}
