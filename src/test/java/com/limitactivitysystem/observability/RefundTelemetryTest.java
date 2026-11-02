package com.limitactivitysystem.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.limitactivitysystem.refund.controller.TicketRefundRequestExceptionHandler;
import com.limitactivitysystem.refund.dto.CreateTicketRefundRequest;
import com.limitactivitysystem.refund.dto.TicketRefundItemResponse;
import com.limitactivitysystem.refund.dto.TicketRefundResponse;
import com.limitactivitysystem.refund.exception.TicketRefundErrorCode;
import com.limitactivitysystem.refund.exception.TicketRefundException;
import com.limitactivitysystem.refund.mapper.TicketRefundMapper;
import com.limitactivitysystem.refund.model.TicketRefundRequest;
import com.limitactivitysystem.refund.service.*;
import com.limitactivitysystem.security.CurrentUserProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RefundTelemetryTest {
    private static final String PRIVATE = "eyJ.private.jwt password=secret passwordHash=$2a$private private-key-material";
    private SimpleMeterRegistry registry;
    private RefundTelemetry telemetry;
    private TicketRefundProcessingService transaction;
    private TicketRefundFailureService failures;
    private TicketRefundApplicationService application;
    private TicketRefundMapper refunds;
    private TicketRefundExecutionService processing;
    private TicketRefundService submission;
    private Logger logger;
    private ListAppender<ILoggingEvent> logs;
    private TicketRefundResponse saved;
    private CreateTicketRefundRequest input;

    @BeforeEach void setup() {
        registry = new SimpleMeterRegistry(); telemetry = new RefundTelemetry(registry);
        transaction = mock(TicketRefundProcessingService.class); failures = mock(TicketRefundFailureService.class);
        application = mock(TicketRefundApplicationService.class); refunds = mock(TicketRefundMapper.class);
        var user = mock(CurrentUserProvider.class);
        when(user.getAuthenticatedUserId()).thenReturn(1L); when(user.getCurrentUserId()).thenReturn(1L);
        processing = new TicketRefundExecutionService(transaction, failures, user, refunds, telemetry);
        submission = new TicketRefundService(user, Clock.fixed(Instant.parse("2026-10-31T12:00:00Z"), ZoneOffset.UTC), application, telemetry);
        var time = LocalDateTime.of(2026, 10, 31, 12, 0);
        var item = new TicketRefundItemResponse(100L, 10L, 30L, BigDecimal.TEN, new BigDecimal("0.15"),
                new BigDecimal("8.50"), "SUCCEEDED", null, time, "SIMULATED_REFUND_SUCCEEDED");
        saved = new TicketRefundResponse(10L, 1L, 20L, "private-business-request-id", PRIVATE, time, "SUCCEEDED", time, time, "SIMULATED_REFUND_SUCCEEDED", List.of(item));
        input = new CreateTicketRefundRequest(); input.setRequestId(saved.requestId()); input.setOrderId(20L);
        input.setTicketIds(List.of(30L)); input.setReason(PRIVATE);
        var request = new TicketRefundRequest(); request.setRefundId(10L); request.setBuyerId(1L); request.setOrderId(20L);
        when(refunds.findById(10L)).thenReturn(request); when(refunds.findItems(10L)).thenReturn(saved.items());
        logger = (Logger) LoggerFactory.getLogger(RefundTelemetry.class);
        logs = new ListAppender<>(); logs.start(); logger.addAppender(logs); MDC.put("requestId", "server-request-id");
    }

    @AfterEach void cleanup() { logger.detachAppender(logs); logs.stop(); registry.close(); MDC.remove("requestId"); }

    @Test void successfulProcessingAndReplayCountCallerOutcomesWithoutCountingMoneyTwice() {
        when(transaction.process(10L)).thenReturn(saved);
        assertThat(processing.process(10L)).isSameAs(saved); assertThat(processing.process(10L)).isSameAs(saved);
        assertThat(registry.get("refund.success").counter().count()).isEqualTo(2);
        assertThat(registry.get("refund.requested").counter().count()).isZero();
        assertThat(duration("PROCESS").count()).isEqualTo(2);
        assertThat(duration("PROCESS").totalTime(TimeUnit.NANOSECONDS)).isPositive();
        assertThat(totalFailures()).isZero(); verifyNoInteractions(failures); verifyNoInteractions(refunds);
        assertThat(logs.list).hasSize(2).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).contains("requestId=server-request-id", "refundId=10", "orderId=20",
                    "userId=1", "ticketIds=[30]", "operation=PROCESS", "result=SUCCEEDED", "failureReason=NONE", "durationMs=");
            assertPrivateDataAbsent(event);
        });
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag -> {
            assertThat(tag.getKey()).isIn("reason", "operation");
            assertThat(tag.getValue()).isIn("APPLICATION", "PROCESS", "ALREADY_REFUNDED", "INVALID_STATE", "RESOURCE_INCONSISTENCY", "TECHNICAL_ERROR");
        }));
    }

    @Test void applicationAndReplayCountRequestsWithoutCountingProcessingSuccess() {
        when(application.apply(eq(1L), same(input), any())).thenReturn(saved);
        submission.submit(input); submission.submit(input);
        assertThat(registry.get("refund.requested").counter().count()).isEqualTo(2);
        assertThat(registry.get("refund.success").counter().count()).isZero();
        assertThat(duration("APPLICATION").count()).isEqualTo(2); assertThat(totalFailures()).isZero();
        assertThat(logs.list).hasSize(2).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).contains("refundId=10", "orderId=20", "ticketIds=[30]", "result=ACCEPTED");
            assertPrivateDataAbsent(event);
        });
    }

    @Test void idempotencyRecoveryInsideAnApplicationCountsOneRequestAndOneDuration() {
        when(application.apply(eq(1L), same(input), any())).thenThrow(new DuplicateKeyException("uq_refund_request " + PRIVATE)).thenReturn(saved);
        assertThat(submission.submit(input)).isSameAs(saved);
        assertThat(registry.get("refund.requested").counter().count()).isEqualTo(1);
        assertThat(duration("APPLICATION").count()).isEqualTo(1); assertThat(totalFailures()).isZero();
        assertThat(logs.list).singleElement().satisfies(this::assertPrivateDataAbsent);
    }

    @ParameterizedTest @CsvSource({"REFUND_TICKET_NOT_VALID,ALREADY_REFUNDED", "REFUND_NOT_PENDING,INVALID_STATE",
            "REFUND_TICKET_NOT_IN_ORDER,INVALID_STATE", "REFUND_ALREADY_REQUESTED,INVALID_STATE",
            "REFUND_RESOURCE_CONFLICT,RESOURCE_INCONSISTENCY", "REFUND_AMOUNT_CALCULATION_ERROR,RESOURCE_INCONSISTENCY"})
    void processingBusinessFailuresKeepTheirCodesAndUseBoundedCategories(TicketRefundErrorCode code, String reason) {
        var failure = new TicketRefundException(code, PRIVATE); when(transaction.process(10L)).thenThrow(failure);
        assertThatThrownBy(() -> processing.process(10L)).isSameAs(failure);
        assertFailure("PROCESS", reason); verifyNoInteractions(failures);
        assertThat(logs.list.get(0).getFormattedMessage()).contains("refundId=10", "orderId=20", "userId=1", "ticketIds=[30]", "failureCode=" + code.name());
    }

    @ParameterizedTest @ValueSource(strings = {"CONNECTION", "WRAPPED", "UNKNOWN", "ERROR"})
    void processingTechnicalFailuresArePreservedWithoutLeakingExceptionMessages(String type) {
        Throwable failure = switch (type) {
            case "CONNECTION" -> new DataAccessResourceFailureException(PRIVATE);
            case "WRAPPED" -> new org.apache.ibatis.exceptions.PersistenceException(PRIVATE, new IllegalStateException(PRIVATE));
            case "ERROR" -> new AssertionError(PRIVATE);
            default -> new IllegalStateException(PRIVATE);
        };
        when(transaction.process(10L)).thenThrow(failure);
        assertThatThrownBy(() -> processing.process(10L)).isSameAs(failure);
        assertFailure("PROCESS", "TECHNICAL_ERROR");
    }

    @Test void failedDiagnosticReadsDoNotMaskTheOriginalFailureOrPreventMetrics() {
        var failure = new DataAccessResourceFailureException(PRIVATE); when(transaction.process(10L)).thenThrow(failure);
        when(refunds.findById(10L)).thenThrow(new DataAccessResourceFailureException(PRIVATE));
        assertThatThrownBy(() -> processing.process(10L)).isSameAs(failure);
        assertFailure("PROCESS", "TECHNICAL_ERROR");
        assertThat(logs.list.get(0).getFormattedMessage()).contains("refundId=10", "orderId=null", "ticketIds=[]");
    }

    @Test void unauthorizedRequestsDoNotLogAnotherBuyersOrderOrTickets() {
        var request = new TicketRefundRequest(); request.setBuyerId(2L); request.setOrderId(999L);
        when(refunds.findById(10L)).thenReturn(request);
        when(transaction.process(10L)).thenThrow(new TicketRefundException(TicketRefundErrorCode.REFUND_ACCESS_DENIED, PRIVATE));
        assertThatThrownBy(() -> processing.process(10L)).isInstanceOf(TicketRefundException.class);
        assertFailure("PROCESS", "INVALID_STATE");
        assertThat(logs.list.get(0).getFormattedMessage()).contains("orderId=null", "ticketIds=[]").doesNotContain("999");
        verify(refunds, never()).findItems(anyLong());
    }

    @Test void failedApplicationsCountOneRequestAndNoProcessingSuccess() {
        var failure = new TicketRefundException(TicketRefundErrorCode.REFUND_WINDOW_CLOSED, PRIVATE);
        when(application.apply(eq(1L), same(input), any())).thenThrow(failure);
        assertThatThrownBy(() -> submission.submit(input)).isSameAs(failure);
        assertThat(registry.get("refund.requested").counter().count()).isEqualTo(1);
        assertFailure("APPLICATION", "INVALID_STATE");
    }

    @Test void ticketIdsAndRequestIdsAreBoundedAndControlCharactersAreSanitized() {
        MDC.put("requestId", "bad\r\nrequest id" + "x".repeat(200));
        var ids = java.util.stream.LongStream.rangeClosed(1, 100).boxed().toList();
        telemetry.record(RefundTelemetry.Operation.PROCESS, new RefundTelemetry.Context(1L, 10L, 20L, ids), null, -1);
        var event = logs.list.get(0);
        assertThat(event.getFormattedMessage()).contains("requestId=bad__request_id", "durationMs=0", "49, 50]").doesNotContain("\r", "\n", "51,");
        MDC.remove("requestId");
        telemetry.record(RefundTelemetry.Operation.PROCESS, new RefundTelemetry.Context(null, 10L, null, null), null, 0);
        assertThat(logs.list.get(1).getFormattedMessage()).contains("requestId=-", "orderId=null", "ticketIds=[]");
    }

    @Test void technicalHttpLogsContainOnlyBoundedDiagnostics() {
        var diagnostic = (Logger) LoggerFactory.getLogger(TicketRefundRequestExceptionHandler.class);
        var events = new ListAppender<ILoggingEvent>(); events.start(); diagnostic.addAppender(events);
        try {
            var error = new DataAccessResourceFailureException(PRIVATE, new java.sql.SQLException(PRIVATE, "08006", 0));
            assertThat(new TicketRefundRequestExceptionHandler().technicalFailure(error).getStatus()).isEqualTo(503);
            assertThat(events.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).contains("DataAccessResourceFailureException", "sqlState=08006");
                assertPrivateDataAbsent(event);
            });
        } finally { diagnostic.detachAppender(events); events.stop(); }
    }

    private io.micrometer.core.instrument.Timer duration(String operation) { return registry.get("refund.duration").tag("operation", operation).timer(); }
    private double totalFailures() { return registry.find("refund.failure").counters().stream().mapToDouble(Counter::count).sum(); }
    private void assertFailure(String operation, String reason) {
        assertThat(registry.get("refund.success").counter().count()).isZero(); assertThat(totalFailures()).isEqualTo(1);
        assertThat(registry.get("refund.failure").tags("operation", operation, "reason", reason).counter().count()).isEqualTo(1);
        assertThat(duration(operation).count()).isEqualTo(1);
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("result=FAILURE", "failureReason=" + reason, "durationMs=");
            assertPrivateDataAbsent(event);
        });
    }
    private void assertPrivateDataAbsent(ILoggingEvent event) {
        assertThat(event.getFormattedMessage()).doesNotContain(PRIVATE, "eyJ", "password", "$2a$", "private-key", "private-business-request-id");
        assertThat(event.getThrowableProxy()).isNull();
    }
}
