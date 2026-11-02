package com.limitactivitysystem.refund.service;

import com.limitactivitysystem.refund.dto.TicketRefundResponse;
import com.limitactivitysystem.refund.exception.TicketRefundException;
import com.limitactivitysystem.security.CurrentUserProvider;
import com.limitactivitysystem.observability.RefundTelemetry;
import com.limitactivitysystem.refund.mapper.TicketRefundMapper;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import static com.limitactivitysystem.refund.exception.TicketRefundErrorCode.REFUND_ALREADY_REQUESTED;

@Service
@Validated
public class TicketRefundExecutionService {
    private final TicketRefundProcessingService processing;
    private final TicketRefundFailureService failures;
    private final CurrentUserProvider currentUser;
    private final TicketRefundMapper refunds;
    private final RefundTelemetry telemetry;

    public TicketRefundExecutionService(TicketRefundProcessingService processing,
            TicketRefundFailureService failures, CurrentUserProvider currentUser, TicketRefundMapper refunds, RefundTelemetry telemetry) {
        this.processing = processing; this.failures = failures; this.currentUser = currentUser;
        this.refunds = refunds; this.telemetry = telemetry;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TicketRefundResponse process(@NotNull @Positive Long refundId) {
        long started = System.nanoTime();
        Long buyerId = null;
        TicketRefundResponse result;
        try {
            buyerId = currentUser.getAuthenticatedUserId();
            result = execute(refundId, buyerId);
        } catch (RuntimeException | Error failure) {
            telemetry.record(RefundTelemetry.Operation.PROCESS, failureContext(refundId, buyerId), failure, System.nanoTime() - started);
            throw failure;
        }
        telemetry.record(RefundTelemetry.Operation.PROCESS, new RefundTelemetry.Context(buyerId, result.refundId(), result.orderId(),
                result.items().stream().map(item -> item.ticketId()).toList()), null, System.nanoTime() - started);
        return result;
    }

    private RefundTelemetry.Context failureContext(Long refundId, Long buyerId) {
        // Read metadata only after rollback. Diagnostic reads must never replace the original failure.
        try {
            var saved = refunds.findById(refundId);
            if (saved != null && saved.getBuyerId().equals(buyerId)) {
                return new RefundTelemetry.Context(buyerId, refundId, saved.getOrderId(),
                        refunds.findItems(refundId).stream().map(item -> item.ticketId()).toList());
            }
        } catch (RuntimeException ignored) { /* The database may still be unavailable. */ }
        return new RefundTelemetry.Context(buyerId, refundId, null, java.util.List.of());
    }

    private TicketRefundResponse execute(Long refundId, Long buyerId) {
        try { return processing.process(refundId); }
        catch (DataAccessException failure) {
            // The processing proxy has completed rollback before any failure result is saved.
            if (failure instanceof DuplicateKeyException
                    && String.valueOf(failure.getMostSpecificCause().getMessage()).contains("uq_refund_ticket_claim")) {
                throw new TicketRefundException(REFUND_ALREADY_REQUESTED, "Another accepted refund owns this ticket");
            }
            try { failures.record(refundId, buyerId, failure.getClass().getSimpleName()); }
            catch (RuntimeException recordingFailure) { failure.addSuppressed(recordingFailure); }
            throw failure;
        }
    }
}
