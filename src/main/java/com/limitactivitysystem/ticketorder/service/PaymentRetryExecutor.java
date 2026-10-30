package com.limitactivitysystem.ticketorder.service;

import com.limitactivitysystem.observability.PaymentTelemetry;
import com.limitactivitysystem.ticketorder.dto.TicketPaymentResponse;
import com.limitactivitysystem.ticketorder.exception.PaymentConcurrencyException;
import com.limitactivitysystem.ticketorder.exception.PaymentConcurrencyException.Reason;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.whitelist.exception.QualificationDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

@Service
public class PaymentRetryExecutor {
    private static final int MAX_ATTEMPTS = 3;
    private final TicketPaymentTransactionService payment;
    private final PaymentRetryBackoff backoff;
    private final PaymentTelemetry telemetry;

    public PaymentRetryExecutor(TicketPaymentTransactionService payment, PaymentRetryBackoff backoff,
                                PaymentTelemetry telemetry) {
        this.payment = payment;
        this.backoff = backoff;
        this.telemetry = telemetry;
    }

    /** Every call crosses the payment proxy: REQUIRES_NEW must finish rollback before backoff. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TicketPaymentResponse pay(Long userId, Long orderId) {
        long started = System.nanoTime();
        int attempts = 0;
        Reason lastConflict = null;
        boolean successful = false;
        try {
            while (true) {
                attempts++;
                try {
                    var result = payment.pay(orderId);
                    successful = true;
                    return result;
                } catch (RuntimeException failure) {
                    Reason reason = retryReason(failure);
                    if (reason == null) { throw failure; }
                    lastConflict = reason;
                    if (attempts == MAX_ATTEMPTS) {
                        throw new PaymentConcurrencyException(reason, attempts, failure);
                    }
                }
                backoff.pause(attempts);
                telemetry.recordRetry(lastConflict);
            }
        } finally {
            if (lastConflict != null) {
                telemetry.recordRetries(userId, orderId, attempts - 1, lastConflict, successful, System.nanoTime() - started);
            }
        }
    }

    private Reason retryReason(RuntimeException failure) {
        if (failure instanceof TicketOrderException || failure instanceof QualificationDeniedException) { return null; }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                if (sql.getErrorCode() == 1213 && "40001".equals(sql.getSQLState())) { return Reason.DEADLOCK; }
                // Connector/J can report server error 1205 as a transaction rollback (SQLState 40001).
                if (sql.getErrorCode() == 1205 && ("HY000".equals(sql.getSQLState()) || "40001".equals(sql.getSQLState()))) {
                    return Reason.LOCK_WAIT_TIMEOUT;
                }
            }
        }
        return null;
    }
}
