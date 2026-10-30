package com.limitactivitysystem.ticketorder.service;

import com.limitactivitysystem.observability.PaymentTelemetry;
import com.limitactivitysystem.ticketorder.dto.TicketPaymentResponse;
import com.limitactivitysystem.ticketorder.exception.PaymentConcurrencyException;
import com.limitactivitysystem.ticketorder.exception.TicketOrderErrorCode;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.whitelist.exception.QualificationDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;

import java.sql.SQLException;
import java.util.List;

import static com.limitactivitysystem.ticketorder.exception.PaymentConcurrencyException.Reason.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentRetryExecutorTest {
    @Mock private TicketPaymentTransactionService payment;
    @Mock private PaymentRetryBackoff backoff;
    @Mock private PaymentTelemetry telemetry;
    private PaymentRetryExecutor retries;
    private final TicketPaymentResponse paid = new TicketPaymentResponse(1L, 2L, null, "PAID", null, List.of());

    @BeforeEach void executor() { retries = new PaymentRetryExecutor(payment, backoff, telemetry); }

    @Test void mixedTechnicalConflictsRetryTwiceAndCountEachActualReason() {
        given(payment.pay(1L)).willThrow(conflict(1213, "40001"), conflict(1205, "40001")).willReturn(paid);
        assertThat(retries.pay(5L, 1L)).isSameAs(paid);
        var sequence = inOrder(payment, backoff, telemetry);
        sequence.verify(payment).pay(1L);
        sequence.verify(backoff).pause(1);
        sequence.verify(telemetry).recordRetry(DEADLOCK);
        sequence.verify(payment).pay(1L);
        sequence.verify(backoff).pause(2);
        sequence.verify(telemetry).recordRetry(LOCK_WAIT_TIMEOUT);
        sequence.verify(payment).pay(1L);
        sequence.verify(telemetry).recordRetries(eq(5L), eq(1L), eq(2), eq(LOCK_WAIT_TIMEOUT), eq(true), anyLong());
        sequence.verifyNoMoreInteractions();
    }

    @Test void threeFailuresReturnAnExplicitTechnicalErrorAndNeverStartAFourthAttempt() {
        var failure = conflict(1213, "40001");
        given(payment.pay(1L)).willThrow(failure);
        assertThatThrownBy(() -> retries.pay(5L, 1L)).isInstanceOfSatisfying(PaymentConcurrencyException.class, error -> {
            assertThat(error.getAttempts()).isEqualTo(3);
            assertThat(error.getReason()).isEqualTo(DEADLOCK);
            assertThat(error.getCause()).isSameAs(failure);
        });
        verify(payment, times(3)).pay(1L);
        verify(backoff).pause(1); verify(backoff).pause(2); verifyNoMoreInteractions(backoff);
        verify(telemetry).recordRetries(eq(5L), eq(1L), eq(2), eq(DEADLOCK), eq(false), anyLong());
    }

    @ParameterizedTest @EnumSource(TicketOrderErrorCode.class)
    void noBusinessRejectionIsRetried(TicketOrderErrorCode code) {
        var failure = new TicketOrderException(code, "Rejected");
        given(payment.pay(1L)).willThrow(failure);
        assertThatThrownBy(() -> retries.pay(5L, 1L)).isSameAs(failure);
        verify(payment).pay(1L); verifyNoInteractions(backoff, telemetry);
    }

    @Test void whitelistRejectionIsNotRetried() {
        var failure = new QualificationDeniedException();
        given(payment.pay(1L)).willThrow(failure);
        assertThatThrownBy(() -> retries.pay(5L, 1L)).isSameAs(failure);
        verify(payment).pay(1L); verifyNoInteractions(backoff, telemetry);
    }

    @Test void sqlErrorsAndConnectionFailuresOutsideTheAllowlistAreNotRetried() {
        for (var failure : List.of(conflict(1064, "42000"), conflict(0, "08001"),
                conflict(1213, "HY000"), new CannotAcquireLockException("No vendor evidence"))) {
            doThrow(failure).when(payment).pay(1L);
            assertThatThrownBy(() -> retries.pay(5L, 1L)).isSameAs(failure);
        }
        verify(payment, times(4)).pay(1L); verifyNoInteractions(backoff, telemetry);
    }

    @Test void aBusinessRejectionAfterRollbackIsReturnedWithoutAnotherRetry() {
        var business = new TicketOrderException(TicketOrderErrorCode.ORDER_EXPIRED, "Expired during backoff");
        given(payment.pay(1L)).willThrow(conflict(1205, "HY000"), business);
        assertThatThrownBy(() -> retries.pay(5L, 1L)).isSameAs(business);
        verify(payment, times(2)).pay(1L); verify(backoff).pause(1); verifyNoMoreInteractions(backoff);
        verify(telemetry).recordRetries(eq(5L), eq(1L), eq(1), eq(LOCK_WAIT_TIMEOUT), eq(false), anyLong());
    }

    @Test void interruptedBackoffPreservesTheInterruptAndDoesNotStartAnotherTransaction() {
        var executor = new PaymentRetryExecutor(payment, new PaymentRetryBackoff(), telemetry);
        given(payment.pay(1L)).willThrow(conflict(1205, "HY000"));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> executor.pay(5L, 1L)).isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(payment).pay(1L);
            verify(telemetry, never()).recordRetry(any());
        } finally { Thread.interrupted(); }
    }

    private CannotAcquireLockException conflict(int code, String state) {
        return new CannotAcquireLockException("Translated JDBC failure", new SQLException("Database concurrency failure", state, code));
    }
}
