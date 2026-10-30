package com.limitactivitysystem.ticketorder.service;

import com.limitactivitysystem.ticketorder.dto.TicketPaymentResponse;
import com.limitactivitysystem.ticketorder.exception.TicketOrderErrorCode;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.observability.PaymentTelemetry;
import com.limitactivitysystem.security.CurrentUserProvider;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class TicketPaymentService {
    private final PaymentRetryExecutor payment;
    private final TicketPaymentFailureService failures;
    private final CurrentUserProvider currentUser;
    private final PaymentTelemetry telemetry;

    public TicketPaymentService(PaymentRetryExecutor payment, TicketPaymentFailureService failures,
                                CurrentUserProvider currentUser, PaymentTelemetry telemetry) {
        this.payment = payment;
        this.failures = failures;
        this.currentUser = currentUser;
        this.telemetry = telemetry;
    }

    /** Keep failure recording outside the failed payment transaction and any caller snapshot. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TicketPaymentResponse pay(@NotNull @Positive Long orderId) {
        long started = System.nanoTime();
        Long userId = null;
        TicketPaymentResponse result;
        try {
            // Principal extraction performs no SQL; the payment transaction still locks Order first.
            userId = currentUser.getAuthenticatedUserId();
            result = executePayment(userId, orderId);
        } catch (RuntimeException | Error failure) {
            telemetry.record(userId, orderId, failure, System.nanoTime() - started);
            throw failure;
        }
        telemetry.record(userId, orderId, null, System.nanoTime() - started);
        return result;
    }

    private TicketPaymentResponse executePayment(Long userId, Long orderId) {
        try {
            return payment.pay(userId, orderId);
        } catch (TicketOrderException exception) {
            if (exception.getErrorCode() != TicketOrderErrorCode.INSUFFICIENT_INVENTORY) { throw exception; }
            // The payment proxy has completed rollback. Technical failures never enter this branch.
            TicketPaymentResponse paid = failures.closeForInsufficientInventory(orderId);
            if (paid != null) { return paid; }
            throw exception;
        }
    }
}
