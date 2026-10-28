package com.limitactivitysystem.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.limitactivitysystem.security.CurrentUserProvider;
import com.limitactivitysystem.ticketorder.dto.TicketPaymentResponse;
import com.limitactivitysystem.ticketorder.exception.TicketOrderErrorCode;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.ticketorder.service.TicketPaymentFailureService;
import com.limitactivitysystem.ticketorder.service.TicketPaymentService;
import com.limitactivitysystem.ticketorder.service.TicketPaymentTransactionService;
import com.limitactivitysystem.whitelist.exception.QualificationDeniedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentTelemetryTest {
    @Mock private TicketPaymentTransactionService transaction;
    @Mock private TicketPaymentFailureService closure;
    @Mock private CurrentUserProvider user;
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private TicketPaymentService payment;
    private Logger logger;
    private ListAppender<ILoggingEvent> logs;
    private final TicketPaymentResponse paid = new TicketPaymentResponse(10L, 20L, BigDecimal.TEN, "PAID", LocalDateTime.of(2026, 10, 26, 20, 0), List.of());

    @BeforeEach void captureOnlyThePaymentLogger() {
        given(user.getAuthenticatedUserId()).willReturn(1L);
        payment = new TicketPaymentService(transaction, closure, user, new PaymentTelemetry(registry));
        logger = (Logger) LoggerFactory.getLogger(PaymentTelemetry.class);
        logs = new ListAppender<>(); logs.start(); logger.addAppender(logs);
        MDC.put("requestId", "server-request-id");
    }
    @AfterEach void cleanup() { logger.detachAppender(logs); logs.stop(); registry.close(); MDC.remove("requestId"); }

    @Test void eachSuccessfulRequestIncludingAReplayIsCountedOnce() {
        given(transaction.pay(10L)).willReturn(paid);
        assertThat(payment.pay(10L)).isSameAs(paid); assertThat(payment.pay(10L)).isSameAs(paid);
        assertThat(registry.get("payment.success").counter().count()).isEqualTo(2);
        assertThat(registry.get("payment.duration").timer().count()).isEqualTo(2);
        assertThat(registry.get("payment.duration").timer().totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isPositive();
        assertThat(totalFailures()).isZero(); verifyNoInteractions(closure);
        assertThat(logs.list).hasSize(2).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).contains("requestId=server-request-id", "userId=1", "orderId=10",
                    "paymentResult=SUCCESS", "failureReason=NONE", "durationMs=");
            assertThat(event.getThrowableProxy()).isNull();
        });
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag -> assertThat(tag.getKey()).isEqualTo("reason")));
    }

    @ParameterizedTest @CsvSource({"ORDER_EXPIRED,ORDER_EXPIRED", "PURCHASE_LIMIT_EXCEEDED,PURCHASE_LIMIT",
            "INSUFFICIENT_INVENTORY,INVENTORY", "SEAT_NOT_AVAILABLE,SEAT_CONFLICT", "ADJACENT_SEATS_UNAVAILABLE,SEAT_CONFLICT",
            "SEAT_REQUIREMENT_NOT_SATISFIED,SEAT_CONFLICT", "ORDER_NOT_PENDING,BUSINESS_REJECTED", "ORDER_ACCESS_DENIED,BUSINESS_REJECTED"})
    void businessReasonsAreBoundedAndExceptionsRemainUnchanged(TicketOrderErrorCode code, String reason) {
        var failure = new TicketOrderException(code, "A private message that must not be logged");
        given(transaction.pay(10L)).willThrow(failure);
        assertThatThrownBy(() -> payment.pay(10L)).isSameAs(failure);
        assertFailure(reason);
        if (code != TicketOrderErrorCode.INSUFFICIENT_INVENTORY) { verifyNoInteractions(closure); }
        assertThat(logs.list.get(0).getFormattedMessage()).contains("failureCode=" + code.name()).doesNotContain(failure.getMessage());
    }

    @Test void changedWhitelistHasItsOwnFailureReason() {
        var failure = new QualificationDeniedException(); given(transaction.pay(10L)).willThrow(failure);
        assertThatThrownBy(() -> payment.pay(10L)).isSameAs(failure);
        assertFailure("QUALIFICATION_DENIED"); verifyNoInteractions(closure);
    }

    @ParameterizedTest @ValueSource(strings = {"UNKNOWN", "CONNECTION", "WRAPPED", "ERROR"})
    void technicalFailuresAreRethrownAndSecretsCannotEnterLogs(String type) {
        String secret = "eyJ.private.jwt password=secret passwordHash=$2a$private private-key-material";
        Throwable failure = switch (type) {
            case "CONNECTION" -> new DataAccessResourceFailureException(secret);
            case "WRAPPED" -> new org.apache.ibatis.exceptions.PersistenceException("Wrapped technical failure", new IllegalStateException(secret));
            case "ERROR" -> new AssertionError(secret);
            default -> new IllegalStateException(secret);
        };
        given(transaction.pay(10L)).willThrow(failure);
        assertThatThrownBy(() -> payment.pay(10L)).isSameAs(failure);
        assertFailure("TECHNICAL_ERROR"); verifyNoInteractions(closure);
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("eyJ", "password", "passwordHash", "$2a$", "private-key");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test void aConcurrentPaidResultAfterStockFailureCountsAsOneSuccessfulOutcome() {
        given(transaction.pay(10L)).willThrow(new TicketOrderException(TicketOrderErrorCode.INSUFFICIENT_INVENTORY, "Stock lost"));
        given(closure.closeForInsufficientInventory(10L)).willReturn(paid);
        assertThat(payment.pay(10L)).isSameAs(paid);
        assertThat(registry.get("payment.success").counter().count()).isEqualTo(1); assertThat(totalFailures()).isZero();
        assertThat(registry.get("payment.duration").timer().count()).isEqualTo(1);
    }

    @Test void closureTechnicalFailureIsNotMisreportedAsAnInventoryResult() {
        given(transaction.pay(10L)).willThrow(new TicketOrderException(TicketOrderErrorCode.INSUFFICIENT_INVENTORY, "Stock lost"));
        var failure = new DataAccessResourceFailureException("Connection failed while closing");
        given(closure.closeForInsufficientInventory(10L)).willThrow(failure);
        assertThatThrownBy(() -> payment.pay(10L)).isSameAs(failure);
        assertFailure("TECHNICAL_ERROR");
        assertThat(registry.get("payment.failure").tag("reason", "INVENTORY").counter().count()).isZero();
    }

    @Test void requestIdIsSanitizedAndMissingIdsAreNotInvented() {
        given(transaction.pay(10L)).willReturn(paid);
        MDC.put("requestId", "bad\r\nrequest id"); payment.pay(10L);
        MDC.remove("requestId"); payment.pay(10L);
        assertThat(logs.list.get(0).getFormattedMessage()).contains("requestId=bad__request_id").doesNotContain("\r", "\n");
        assertThat(logs.list.get(1).getFormattedMessage()).contains("requestId=-");
    }

    private void assertFailure(String reason) {
        assertThat(registry.get("payment.success").counter().count()).isZero(); assertThat(totalFailures()).isEqualTo(1);
        assertThat(registry.get("payment.failure").tag("reason", reason).counter().count()).isEqualTo(1);
        assertThat(registry.get("payment.duration").timer().count()).isEqualTo(1);
        assertThat(logs.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage()).contains(
                "requestId=server-request-id", "userId=1", "orderId=10", "paymentResult=FAILURE", "failureReason=" + reason, "durationMs="));
    }
    private double totalFailures() { return registry.find("payment.failure").counters().stream().mapToDouble(Counter::count).sum(); }
}
