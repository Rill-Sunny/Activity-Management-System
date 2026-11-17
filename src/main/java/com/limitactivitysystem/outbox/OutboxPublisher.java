package com.limitactivitysystem.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.limitactivitysystem.messaging.RabbitMqTopology;
import com.limitactivitysystem.ticketorder.mapper.DatabaseTimeMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final DatabaseTimeMapper databaseTime;
    private final OutboxTraceSupport traceSupport;
    private final long confirmTimeoutMs;
    private final String eventsExchange;

    public OutboxPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper, DatabaseTimeMapper databaseTime,
            OutboxTraceSupport traceSupport,
            @Value("${outbox.publisher-confirm-timeout-ms:10000}") long confirmTimeoutMs,
            @Value("${messaging.rabbit.name-prefix:}") String namePrefix) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.databaseTime = databaseTime;
        this.traceSupport = traceSupport;
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.eventsExchange = namePrefix + RabbitMqTopology.EVENTS_EXCHANGE;
    }

    public void publish(OutboxEvent event) {
        String routingKey = routingKey(event.getEventType());
        Span span = traceSupport.startPublishSpan(event, routingKey);
        long started = System.nanoTime();
        try (Scope ignored = span.makeCurrent();
             var logs = new com.limitactivitysystem.observability.TraceLogContext(span, "outbox.publish")) {
            Message message = buildMessage(event);
            CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
            rabbitTemplate.send(eventsExchange, routingKey, message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
            if (correlation.getReturned() != null) {
                var returned = correlation.getReturned();
                throw new IllegalStateException("RabbitMQ returned unroutable event: reply="
                        + returned.getReplyCode() + " " + returned.getReplyText()
                        + ", exchange=" + returned.getExchange() + ", routingKey=" + returned.getRoutingKey());
            }
            if (!confirm.isAck()) {
                throw new IllegalStateException("RabbitMQ publisher confirm NACK: " + confirm.getReason());
            }
            String traceId = span.getSpanContext().isValid() ? span.getSpanContext().getTraceId() : "-";
            log.atInfo().addKeyValue("duration", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .addKeyValue("errorCode", "NONE").addKeyValue("eventId", event.getEventId())
                    .log("Outbox event confirmed by RabbitMQ: eventId={} eventType={} routingKey={} traceId={}",
                    event.getEventId(), event.getEventType(), routingKey, traceId);
        } catch (InterruptedException exception) {
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR).setAttribute("error.type", "InterruptedException");
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for RabbitMQ confirm for event "
                    + event.getEventId(), exception);
        } catch (Exception exception) {
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR).setAttribute("error.type", exception.getClass().getSimpleName());
            throw new IllegalStateException("RabbitMQ publish was not confirmed for outbox event "
                    + event.getEventId(), exception);
        } finally {
            span.end();
        }
    }

    private Message buildMessage(OutboxEvent event) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(event.getEventId());
        properties.setType(event.getEventType());
        properties.setHeader("x-event-id", event.getEventId());
        properties.setHeader("x-event-type", event.getEventType());
        SpanContext traceContext = Span.current().getSpanContext();
        if (traceContext.isValid()) {
            properties.setHeader("traceparent", "00-" + traceContext.getTraceId() + "-" + traceContext.getSpanId()
                    + (traceContext.getTraceFlags().isSampled() ? "-01" : "-00"));
            Span.current().setAttribute("messaging.trace_id", traceContext.getTraceId());
        }
        if ("ORDER_EXPIRED".equals(event.getEventType()) || "RESCHEDULE_WINDOW_CLOSED".equals(event.getEventType())) {
            JsonNode payload = objectMapper.readTree(event.getPayload());
            LocalDateTime expiresAt = LocalDateTime.parse(payload.path("expiresAt").asText());
            long remainingMs = Duration.between(databaseTime.currentTime(), expiresAt).toMillis();
            // If the relay wakes after the business deadline, let the message move through DLX immediately.
            properties.setExpiration(Long.toString(Math.max(1L, remainingMs)));
        }
        return new Message(event.getPayload().getBytes(StandardCharsets.UTF_8), properties);
    }

    private String routingKey(String eventType) {
        return switch (eventType) {
            case "ORDER_EXPIRED" -> "order.expiry.delay";
            case "PAYMENT_SUCCEEDED" -> "notification.payment.success";
            case "REFUND_SUCCEEDED" -> "notification.refund.success";
            case "TICKET_QUALIFICATION_CHANGED" -> "ticket.qualification.changed";
            case "SESSION_RESCHEDULED" -> "session.reschedule.notice";
            case "RESCHEDULE_WINDOW_CLOSED" -> "session.reschedule.wait";
            case "SESSION_CANCELLED", "ACTIVITY_CANCELLED" -> "cancellation.execute";
            default -> throw new IllegalArgumentException("No RabbitMQ route configured for " + eventType);
        };
    }
}
