package com.limitactivitysystem.observability;

import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.whitelist.exception.QualificationDeniedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class PaymentTelemetry {
    public enum FailureReason {
        ORDER_EXPIRED, QUALIFICATION_DENIED, PURCHASE_LIMIT, INVENTORY, SEAT_CONFLICT, BUSINESS_REJECTED, TECHNICAL_ERROR
    }
    private static final Logger log = LoggerFactory.getLogger(PaymentTelemetry.class);
    private final Counter success;
    private final Map<FailureReason, Counter> failures = new EnumMap<>(FailureReason.class);
    private final Timer duration;

    public PaymentTelemetry(MeterRegistry registry) {
        success = registry.counter("payment.success");
        for (FailureReason reason : FailureReason.values()) {
            failures.put(reason, registry.counter("payment.failure", "reason", reason.name()));
        }
        duration = registry.timer("payment.duration");
    }

    public void record(Long userId, Long orderId, Throwable failure, long elapsedNanos) {
        long elapsed = Math.max(0, elapsedNanos);
        FailureReason reason = failure == null ? null : classify(failure);
        if (reason == null) { success.increment(); } else { failures.get(reason).increment(); }
        duration.record(elapsed, TimeUnit.NANOSECONDS);
        String code = failure instanceof TicketOrderException business ? business.getErrorCode().name()
                : failure instanceof QualificationDeniedException ? QualificationDeniedException.CODE : "-";
        // Only bounded enums and trusted numeric IDs enter the log. Never pass the exception or its message.
        log.info("Ticket payment requestId={} userId={} orderId={} paymentResult={} failureReason={} failureCode={} durationMs={}",
                safeRequestId(), userId, orderId, reason == null ? "SUCCESS" : "FAILURE",
                reason == null ? "NONE" : reason.name(), code, TimeUnit.NANOSECONDS.toMillis(elapsed));
    }

    private FailureReason classify(Throwable failure) {
        if (failure instanceof QualificationDeniedException) { return FailureReason.QUALIFICATION_DENIED; }
        if (failure instanceof TicketOrderException business) {
            return switch (business.getErrorCode()) {
                case ORDER_EXPIRED -> FailureReason.ORDER_EXPIRED;
                case PURCHASE_LIMIT_EXCEEDED -> FailureReason.PURCHASE_LIMIT;
                case INSUFFICIENT_INVENTORY -> FailureReason.INVENTORY;
                case SEAT_NOT_AVAILABLE, ADJACENT_SEATS_UNAVAILABLE, SEAT_REQUIREMENT_NOT_SATISFIED -> FailureReason.SEAT_CONFLICT;
                default -> FailureReason.BUSINESS_REJECTED;
            };
        }
        if (failure instanceof AuthenticationException) { return FailureReason.BUSINESS_REJECTED; }
        return FailureReason.TECHNICAL_ERROR;
    }

    private String safeRequestId() {
        String value = MDC.get("requestId");
        if (value == null) { return "-"; }
        String safe = value.replaceAll("[\\p{Cntrl}\\s]", "_");
        return safe.substring(0, Math.min(safe.length(), 128));
    }
}
