package com.limitactivitysystem.observability;

import com.limitactivitysystem.cancellation.CancellationMapper;
import com.limitactivitysystem.product.exception.ProductException;
import com.limitactivitysystem.refund.exception.TicketRefundException;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.whitelist.exception.QualificationDeniedException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Invocation outcomes, including replays; these meters are not financial settlement ledgers. */
@Component
public class BusinessTelemetry {
    public enum Result { SUCCESS, INSUFFICIENT_STOCK, LIMIT_EXCEEDED, QUALIFICATION_DENIED,
        ORDER_EXPIRED, BUSINESS_REJECTED, TECHNICAL_FAILURE, RETRY, DLQ }
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private static final TextMapGetter<Map<String, Object>> HEADERS = new TextMapGetter<>() {
        public Iterable<String> keys(Map<String, Object> carrier) { return carrier.keySet(); }
        public String get(Map<String, Object> carrier, String key) {
            Object value = carrier.get(key); return value == null ? null : value.toString();
        }
    };
    private final MeterRegistry meters;
    private final Tracer tracer;
    private final TaskTraceLinks taskLinks;

    public BusinessTelemetry(MeterRegistry meters, OpenTelemetry telemetry, TaskTraceLinks taskLinks) {
        this.meters = meters;
        this.tracer = telemetry.getTracer("com.limitactivitysystem.business");
        this.taskLinks = taskLinks;
        // Publish zero baselines before the first failure so rate/increase can observe the first event.
        for (String operation : new String[]{"ticket.payment", "product.payment"}) {
            for (Result result : Result.values()) {
                if (result != Result.RETRY && result != Result.DLQ) {
                    meters.timer(operation, "result", result.name());
                }
            }
        }
        for (String policy : new String[]{"NORMAL", "RESCHEDULE", "CANCELLATION", "UNKNOWN"}) {
            for (Result result : new Result[]{Result.SUCCESS, Result.BUSINESS_REJECTED, Result.TECHNICAL_FAILURE}) {
                meters.timer("ticket.refund", "policy", policy, "result", result.name());
            }
        }
        for (String consumer : new String[]{"consume", "consumeCancellation", "consumeRescheduleNotice", "consumeRescheduleRetention",
                "consumeOrderExpiry", "consumePaymentNotification", "consumeRefundNotification"}) {
            for (Result result : new Result[]{Result.SUCCESS, Result.RETRY, Result.DLQ, Result.TECHNICAL_FAILURE}) {
                meters.timer("mq.consume", "consumer", consumer, "result", result.name());
            }
        }
    }

    public Object invoke(String operation, MethodInvocation invocation) throws Throwable {
        var builder = tracer.spanBuilder(operation);
        Message message = invocation.getArguments().length > 0 && invocation.getArguments()[0] instanceof Message m ? m : null;
        if (message != null) {
            // A delivery starts from its durable W3C carrier, never the scheduler/listener thread's context.
            builder.setParent(W3CTraceContextPropagator.getInstance().extract(Context.root(),
                    message.getMessageProperties().getHeaders(), HEADERS)).setSpanKind(SpanKind.CONSUMER);
        } else {
            SpanContext link = taskLinks.find(operation, invocation.getArguments());
            if (link.isValid() && !link.getTraceId().equals(Span.current().getSpanContext().getTraceId())) {
                builder.addLink(link);
            }
        }
        Span span = builder.startSpan();
        var frame = new Frame(operation, span, meters);
        Frame previous = CURRENT.get();
        var previousMdc = MDC.getCopyOfContextMap();
        long started = System.nanoTime();
        CURRENT.set(frame);
        try (var scope = span.makeCurrent()) {
            if (span.getSpanContext().isValid()) {
                MDC.put("traceId", span.getSpanContext().getTraceId());
                MDC.put("spanId", span.getSpanContext().getSpanId());
            }
            MDC.put("businessOperation", operation);
            if (MDC.get("requestId") == null) { MDC.put("requestId", "-"); }
            addIdentifiers(span, operation, invocation.getArguments(), message);
            try {
                return invocation.proceed();
            } catch (Throwable failure) {
                frame.result = classify(failure);
                frame.code = errorCode(failure);
                // Exception messages can contain SQL parameters or credentials; record only the type/code.
                span.setAttribute("error.type", failure.getClass().getSimpleName());
                if (frame.result == Result.TECHNICAL_FAILURE) { span.setStatus(StatusCode.ERROR); }
                throw failure;
            } finally {
                long elapsed = System.nanoTime() - started;
                span.setAttribute("business.result", frame.result.name());
                span.setAttribute("error.code", frame.code);
                var timer = Timer.builder(operation).tag("result", frame.result.name());
                if (operation.equals("ticket.refund")) { timer.tag("policy", frame.policy); }
                if (operation.equals("mq.consume")) { timer.tag("consumer", invocation.getMethod().getName()); }
                timer.register(meters).record(elapsed, TimeUnit.NANOSECONDS);
                LoggerFactory.getLogger(BusinessTelemetry.class).atInfo()
                        .addKeyValue("duration", TimeUnit.NANOSECONDS.toMillis(elapsed))
                        .addKeyValue("errorCode", frame.code).addKeyValue("result", frame.result.name())
                        .addKeyValue("policy", frame.policy).log("Business operation completed");
            }
        } finally {
            span.end();
            if (previous == null) { CURRENT.remove(); } else { CURRENT.set(previous); }
            if (previousMdc == null) { MDC.clear(); } else { MDC.setContextMap(previousMdc); }
        }
    }

    /** Called after the accepted refund row is read, without extra SQL or changing its policy. */
    public static void refundPolicy(String policy) {
        Frame frame = CURRENT.get();
        if (frame != null && frame.operation.equals("ticket.refund")) {
            frame.policy = switch (policy == null ? "" : policy) {
                case "NORMAL_REFUND" -> "NORMAL";
                case "SESSION_RESCHEDULE_REFUND" -> "RESCHEDULE";
                case "CANCELLATION_REFUND" -> "CANCELLATION";
                default -> "UNKNOWN";
            };
            frame.span.setAttribute("refund.policy", frame.policy);
        }
    }

    /** Listener failures are handled internally; expose their outcome before ACK or DLQ rejection. */
    public static void consumerFailure(boolean retrySent, Throwable failure) {
        Frame frame = CURRENT.get();
        if (frame != null && frame.operation.equals("mq.consume")) {
            frame.result = retrySent ? Result.RETRY : Result.DLQ;
            frame.code = "CONSUMER_PROCESSING_FAILED";
            frame.span.setAttribute("error.type", failure.getClass().getSimpleName());
            frame.span.setStatus(StatusCode.ERROR);
        }
    }

    public static void productRetry() {
        Frame frame = CURRENT.get();
        if (frame != null) { frame.meters.counter("product.payment.retry", "reason", "DATABASE_CONCURRENCY").increment(); }
    }

    public static void refundRetry() {
        Frame frame = CURRENT.get();
        if (frame != null) { frame.meters.counter("ticket.refund.retry", "policy", frame.policy).increment(); }
    }

    public static Result classify(Throwable failure) {
        String code = errorCode(failure);
        return switch (code) {
            case "INSUFFICIENT_INVENTORY", "INSUFFICIENT_SKU_STOCK", "PRODUCT_ORDER_OUT_OF_STOCK", "SEAT_NOT_AVAILABLE",
                 "ADJACENT_SEATS_UNAVAILABLE", "SEAT_REQUIREMENT_NOT_SATISFIED" -> Result.INSUFFICIENT_STOCK;
            case "PURCHASE_LIMIT_EXCEEDED", "PRODUCT_PURCHASE_LIMIT_EXCEEDED" -> Result.LIMIT_EXCEEDED;
            case "QUALIFICATION_DENIED", "PRODUCT_QUALIFICATION_DENIED" -> Result.QUALIFICATION_DENIED;
            case "ORDER_EXPIRED", "PRODUCT_ORDER_EXPIRED" -> Result.ORDER_EXPIRED;
            case "TECHNICAL_FAILURE", "PRODUCT_PAYMENT_RETRIES_EXHAUSTED", "PAYMENT_CONCURRENCY_RETRY_EXHAUSTED", "REFUND_AMOUNT_CALCULATION_ERROR" -> Result.TECHNICAL_FAILURE;
            default -> Result.BUSINESS_REJECTED;
        };
    }

    private static String errorCode(Throwable failure) {
        if (failure instanceof com.limitactivitysystem.product.exception.ProductOrderOutOfStockException) {
            return "PRODUCT_ORDER_OUT_OF_STOCK";
        }
        if (failure instanceof ProductException e) { return e.code().name(); }
        if (failure instanceof TicketOrderException e) { return e.getErrorCode().name(); }
        if (failure instanceof TicketRefundException e) { return e.getErrorCode().name(); }
        if (failure instanceof com.limitactivitysystem.ticketorder.exception.PaymentConcurrencyException) {
            return com.limitactivitysystem.ticketorder.exception.PaymentConcurrencyException.CODE;
        }
        if (failure instanceof QualificationDeniedException) { return "QUALIFICATION_DENIED"; }
        if (failure instanceof org.springframework.security.core.AuthenticationException) { return "AUTHENTICATION_REJECTED"; }
        if (failure instanceof org.springframework.security.access.AccessDeniedException) { return "ACCESS_DENIED"; }
        if (failure instanceof jakarta.validation.ValidationException) { return "VALIDATION_REJECTED"; }
        if (failure instanceof org.springframework.web.server.ResponseStatusException status) {
            return status.getStatusCode().is5xxServerError() ? "TECHNICAL_FAILURE" : "BUSINESS_REJECTED";
        }
        return "TECHNICAL_FAILURE";
    }

    private void addIdentifiers(Span span, String operation, Object[] args, Message message) {
        if (args.length > 0 && args[0] instanceof Long id) {
            String key = operation.equals("ticket.refund") ? "refundId" : operation.equals("qualification.task.process") ? "taskId" : "orderId";
            MDC.put(key, id.toString()); span.setAttribute(key, id);
        }
        if (args.length > 0 && args[0] instanceof CancellationMapper.Item item) {
            MDC.put("taskId", Long.toString(item.itemId())); span.setAttribute("taskId", item.itemId());
        }
        if (message != null) {
            Object id = message.getMessageProperties().getHeader("x-event-id");
            if (id != null && id.toString().matches("[0-9a-fA-F-]{36}")) {
                MDC.put("eventId", id.toString()); span.setAttribute("messaging.message.id", id.toString());
            }
            span.setAttribute("messaging.system", "rabbitmq");
        }
    }

    private static final class Frame {
        final String operation;
        final Span span;
        final MeterRegistry meters;
        Result result = Result.SUCCESS;
        String policy = "UNKNOWN";
        String code = "NONE";
        Frame(String operation, Span span, MeterRegistry meters) { this.operation = operation; this.span = span; this.meters = meters; }
    }
}
