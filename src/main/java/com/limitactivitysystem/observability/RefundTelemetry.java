package com.limitactivitysystem.observability;

import com.limitactivitysystem.refund.exception.TicketRefundException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Component
public class RefundTelemetry {
    public enum Operation { APPLICATION, PROCESS }
    public enum FailureReason { ALREADY_REFUNDED, INVALID_STATE, RESOURCE_INCONSISTENCY, TECHNICAL_ERROR }
    public record Context(Long userId, Long refundId, Long orderId, List<Long> ticketIds) { }
    private static final Logger LOG = LoggerFactory.getLogger(RefundTelemetry.class);
    private final Counter requested;
    private final Counter success;
    private final Map<Operation, Timer> durations = new EnumMap<>(Operation.class);
    private final Map<Operation, Map<FailureReason, Counter>> failures = new EnumMap<>(Operation.class);

    public RefundTelemetry(MeterRegistry registry) {
        requested = registry.counter("refund.requested");
        success = registry.counter("refund.success");
        for (Operation operation : Operation.values()) {
            durations.put(operation, registry.timer("refund.duration", "operation", operation.name()));
            var reasons = new EnumMap<FailureReason, Counter>(FailureReason.class);
            for (FailureReason reason : FailureReason.values()) {
                reasons.put(reason, registry.counter("refund.failure", "operation", operation.name(), "reason", reason.name()));
            }
            failures.put(operation, reasons);
        }
    }

    public void requested() { requested.increment(); }

    /** Counts caller outcomes, including idempotent replays. It is not a money ledger. */
    public void record(Operation operation, Context context, Throwable failure, long elapsedNanos) {
        long elapsed = Math.max(0, elapsedNanos);
        FailureReason reason = failure == null ? null : classify(failure);
        if (reason != null) { failures.get(operation).get(reason).increment(); }
        else if (operation == Operation.PROCESS) { success.increment(); }
        durations.get(operation).record(elapsed, TimeUnit.NANOSECONDS);
        String code = failure instanceof TicketRefundException business ? business.getErrorCode().name() : "-";
        // Log only numeric identifiers and bounded outcomes. No request reason, exception message or credentials.
        LOG.info("Ticket refund requestId={} refundId={} orderId={} userId={} ticketIds={} operation={} result={} failureReason={} failureCode={} durationMs={}",
                safeRequestId(), context.refundId(), context.orderId(), context.userId(), safeTicketIds(context.ticketIds()),
                operation.name(), reason == null ? (operation == Operation.APPLICATION ? "ACCEPTED" : "SUCCEEDED") : "FAILURE",
                reason == null ? "NONE" : reason.name(), code, TimeUnit.NANOSECONDS.toMillis(elapsed));
    }

    private FailureReason classify(Throwable failure) {
        if (failure instanceof TicketRefundException business) {
            return switch (business.getErrorCode()) {
                case REFUND_TICKET_NOT_VALID -> FailureReason.ALREADY_REFUNDED;
                case REFUND_RESOURCE_CONFLICT, REFUND_AMOUNT_CALCULATION_ERROR -> FailureReason.RESOURCE_INCONSISTENCY;
                default -> FailureReason.INVALID_STATE;
            };
        }
        return FailureReason.TECHNICAL_ERROR;
    }

    private List<Long> safeTicketIds(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).filter(id -> id > 0).distinct().sorted().limit(50).toList();
    }
    private String safeRequestId() {
        String value = MDC.get("requestId");
        if (value == null) { return "-"; }
        String safe = value.replaceAll("[\\p{Cntrl}\\s]", "_");
        return safe.substring(0, Math.min(safe.length(), 128));
    }
}
