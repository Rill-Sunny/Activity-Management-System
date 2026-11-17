package com.limitactivitysystem.activity.integration;

import com.limitactivitysystem.activity.model.*;
import com.limitactivitysystem.refund.dto.TicketRefundResponse;
import com.limitactivitysystem.refund.service.TicketRefundExecutionService;
import com.limitactivitysystem.refund.service.TicketRefundProcessingService;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.ticketorder.mapper.TicketMapper;
import com.limitactivitysystem.ticketorder.model.Ticket;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.dao.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import({TicketRefundApplicationMySqlIntegrationTest.DatabaseConfiguration.class,
        TicketRefundProcessingMySqlIntegrationTest.ObservationConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class TicketRefundProcessingMySqlIntegrationTest extends PaymentResourceTestSupport {
    private static final Map<String, Object> RECONCILIATION_AUDIT = new java.util.concurrent.ConcurrentSkipListMap<>();
    @Autowired private TicketMapper tickets;
    @Autowired private io.micrometer.core.instrument.MeterRegistry registry;
    @Autowired private ProcessingObservation observation;
    @Autowired private TicketRefundExecutionService execution;
    @Autowired private TicketRefundProcessingService processing;
    private Activity activity;
    private ActivitySession session;
    private SessionZone firstZone;
    private SessionZone secondZone;
    private Long orderId;
    private List<Ticket> sold;

    @BeforeAll static void resetReconciliationAudit() { RECONCILIATION_AUDIT.clear(); }
    @AfterAll static void saveReconciliationAudit() throws Exception {
        var path = java.nio.file.Path.of("target", "verification", "day11-refund-reconciliation.json");
        java.nio.file.Files.createDirectories(path.getParent());
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(),
                Map.of("script", "tests/sql/refund_invariants.sql", "testCases", RECONCILIATION_AUDIT.size(), "checks", RECONCILIATION_AUDIT));
    }

    @BeforeEach void payForFourTickets() throws Exception {
        var now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class);
        activity = insertDraft(publisher, "PAID");
        jdbc.update("UPDATE activity SET open_time = ?, close_time = ? WHERE activity_id = ?",
                now.minusDays(10), now.plusDays(1), activity.getActivityId());
        session = insertSession(activity);
        jdbc.update("UPDATE activity_session SET start_time = ?, end_time = ?, max_tickets_per_user = 10 WHERE activity_session_id = ?",
                now.plusDays(20), now.plusDays(20).plusHours(2), session.getActivitySessionId());
        firstZone = insertZone(session, "PAID", 5, 5);
        secondZone = insertZone(session, "PAID", 5, 5);
        jdbc.update("UPDATE session_zone SET price = 10.01 WHERE zone_id = ?", firstZone.getZoneId());
        jdbc.update("UPDATE session_zone SET price = 20.03 WHERE zone_id = ?", secondZone.getZoneId());
        publish(activity, publisher).andExpect(status().isNoContent());
        // Deliberately interleave Zone and Seat IDs in the business input.
        var selected = List.of(seatMapper.findByZoneId(secondZone.getZoneId()).get(1),
                seatMapper.findByZoneId(firstZone.getZoneId()).get(0),
                seatMapper.findByZoneId(secondZone.getZoneId()).get(0),
                seatMapper.findByZoneId(firstZone.getZoneId()).get(1));
        orderId = createOrder(selected);
        mvc.perform(post("/ticket-orders/{id}/pay", orderId).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isOk());
        sold = tickets.findByOrderId(orderId);
        assertThat(sold).hasSize(4);
    }

    @AfterEach void clearRefunds(TestInfo test) throws Exception {
        observation.current.remove();
        try {
            var violations = RefundInvariantAssertions.violations(jdbc);
            boolean deliberatelyCorrupt = Set.of("corruptOrPreviouslyRefundedResourcesCannotBeRestored",
                    "nonPendingStatesRequireAnExplicitRecoveryWorkflow", "processingRequiresAuthenticationAndBothRequestAndOrderOwnership",
                    "reconciliationDetectsBrokenRefundSummariesAndResults").contains(test.getTestMethod().orElseThrow().getName());
            RECONCILIATION_AUDIT.put(test.getTestMethod().orElseThrow().getName() + " :: " + test.getDisplayName(), Map.of("deliberatelyCorrupt", deliberatelyCorrupt,
                    "violationCount", violations.size(), "violationTypes", violations.stream().map(row -> row.get("violation")).distinct().sorted().toList()));
            if (deliberatelyCorrupt) { assertThat(violations).as("Deliberately broken fixtures must be visible to reconciliation").isNotEmpty(); }
            else { assertThat(violations).as("Refund SQL reconciliation after " + test.getDisplayName()).isEmpty(); }
        } finally {
            jdbc.update("DELETE FROM ticket_refund_item");
            jdbc.update("DELETE FROM ticket_refund_request");
            // Corruption tests may sever Zone -> Session. Clean these owned IDs even if the chain is broken.
            if (firstZone != null && secondZone != null) {
                jdbc.update("DELETE FROM session_seat WHERE zone_id IN (?, ?)", firstZone.getZoneId(), secondZone.getZoneId());
                jdbc.update("DELETE FROM session_zone WHERE zone_id IN (?, ?)", firstZone.getZoneId(), secondZone.getZoneId());
            }
        }
    }

    @Test void at09RefundOnlyBAndDAtTheirFourActualPaidPrices() throws Exception {
        var now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class);
        var mixed = insertDraft(publisher, "PAID");
        jdbc.update("UPDATE activity SET open_time = ?, close_time = ? WHERE activity_id = ?",
                now.minusDays(10), now.plusDays(1), mixed.getActivityId());
        var mixedSession = insertSession(mixed);
        jdbc.update("UPDATE activity_session SET capacity = 4, max_tickets_per_user = 4, start_time = ?, end_time = ? WHERE activity_session_id = ?",
                now.plusDays(20), now.plusDays(20).plusHours(2), mixedSession.getActivitySessionId());
        var selectedSeats = new ArrayList<SessionSeat>();
        for (String price : List.of("200.00", "400.00", "800.00", "1200.00")) {
            var zone = insertZone(mixedSession, "PAID", 1, 1);
            jdbc.update("UPDATE session_zone SET price = ? WHERE zone_id = ?", new BigDecimal(price), zone.getZoneId());
            selectedSeats.add(seatMapper.findByZoneId(zone.getZoneId()).get(0));
        }
        publish(mixed, publisher).andExpect(status().isNoContent());
        Long mixedOrder = createOrder(mixedSession.getActivitySessionId(), selectedSeats);
        mvc.perform(post("/ticket-orders/{id}/pay", mixedOrder).header(HttpHeaders.AUTHORIZATION, bearer(buyer))).andExpect(status().isOk());
        var four = tickets.findByOrderId(mixedOrder);
        assertThat(four.stream().map(Ticket::getPaidPrice)).containsExactly(new BigDecimal("200.00"), new BigDecimal("400.00"),
                new BigDecimal("800.00"), new BigDecimal("1200.00"));
        double requestedBefore = registry.get("refund.requested").counter().count();
        double successBefore = registry.get("refund.success").counter().count();
        var accepted = acceptBody(Map.of("requestId", "AT-09", "orderId", mixedOrder,
                "ticketIds", List.of(four.get(3).getTicketId(), four.get(1).getTicketId()), "reason", "USER_REQUEST"));
        var completed = process(accepted.refundId());
        assertSucceeded(accepted, completed);
        assertThat(completed.items().stream().map(item -> item.originalPrice())).containsExactly(new BigDecimal("400.00"), new BigDecimal("1200.00"));
        assertThat(completed.items().stream().map(item -> item.refundAmount())).containsExactly(new BigDecimal("340.00"), new BigDecimal("1020.00"));
        assertThat(completed.items()).allSatisfy(item -> assertThat(item.feeRate()).isEqualByComparingTo("0.15"));
        assertThat(jdbc.queryForObject("SELECT SUM(refund_amount) FROM ticket_refund_item WHERE refund_id = ? AND status = 'SUCCEEDED'", BigDecimal.class, accepted.refundId()))
                .isEqualByComparingTo("1360.00");
        var after = tickets.findByOrderId(mixedOrder);
        assertThat(after.stream().map(Ticket::getStatus)).containsExactly("VALID", "REFUNDED", "VALID", "REFUNDED");
        assertThat(orders.findById(mixedOrder).getStatus()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(purchases.findValidCount(buyer.getId(), mixedSession.getActivitySessionId())).isEqualTo(2);
        assertThat(registry.get("refund.requested").counter().count()).isEqualTo(requestedBefore + 1);
        assertThat(registry.get("refund.success").counter().count()).isEqualTo(successBefore + 1);
        for (int i = 0; i < 4; i++) {
            boolean refunded = i == 1 || i == 3;
            assertThat(seatMapper.findById(selectedSeats.get(i).getSeatId()).getAllocationStatus()).isEqualTo(refunded ? "AVAILABLE" : "SOLD");
            assertThat(zoneMapper.findById(selectedSeats.get(i).getZoneId()).getRemainsAmount()).isEqualTo(refunded ? 1 : 0);
        }
        evidence("day11-at09-four-prices-succeeded", Map.of("originalTickets", four, "completed", completed,
                "ticketsAfter", after, "orderStatus", orders.findById(mixedOrder).getStatus(), "validCount", 2,
                "seats", selectedSeats.stream().map(seat -> seatMapper.findById(seat.getSeatId())).toList(),
                "zones", selectedSeats.stream().map(seat -> zoneMapper.findById(seat.getZoneId())).toList()));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void at10ConcurrentSameTicketApplicationsAndProcessingRestoreAndRefundOnlyOnce(boolean sameRequestId) throws Exception {
        var ticket = sold.get(0);
        var firstInput = applicationBody(List.of(ticket));
        var secondInput = sameRequestId ? firstInput : applicationBody(List.of(ticket));
        var barrier = new CyclicBarrier(2);
        var a = async(() -> concurrentSubmitAndProcess(firstInput, barrier));
        var b = async(() -> concurrentSubmitAndProcess(secondInput, barrier));
        var outcomes = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        assertThat(outcomes.stream().map(ConcurrentOutcome::http)).containsExactlyInAnyOrder(201, sameRequestId ? 201 : 409);
        if (sameRequestId) { assertThat(outcomes.get(0).completed()).isEqualTo(outcomes.get(1).completed()); }
        else { assertThat(outcomes.stream().filter(o -> o.http() == 409).findFirst().orElseThrow().code()).isEqualTo("REFUND_ALREADY_REQUESTED"); }
        assertSingleTicketRefund(ticket);
        for (String method : List.of("tryDecrease", "restoreStock", "releaseSold", "markRefunded", "markSucceeded")) {
            assertThat(outcomes.stream().mapToInt(o -> o.probe().writes.getOrDefault(method, 0)).sum()).as(method).isEqualTo(1);
        }
        evidence("day11-at10-" + (sameRequestId ? "same" : "different") + "-request-id",
                Map.of("ticketId", ticket.getTicketId(), "outcomes", outcomes.stream().map(o -> Map.of("http", o.http(), "code", o.code(), "writes", o.probe().writes)).toList(),
                        "successfulRefundAmount", successfulRefundAmount(ticket.getTicketId()), "resourcesRestored", 1));
    }

    private ConcurrentOutcome concurrentSubmitAndProcess(Map<String, Object> input, CyclicBarrier barrier) throws Exception {
        var probe = new Probe(); observation.current.set(probe);
        try {
            barrier.await(10, TimeUnit.SECONDS);
            var response = postJson("/ticket-refunds", buyer, input).andReturn().getResponse();
            // Both acceptances finish before processing so the loser exercises the database claim.
            barrier.await(10, TimeUnit.SECONDS);
            if (response.getStatus() == 201) {
                var accepted = objectMapper.readValue(response.getContentAsString(), TicketRefundResponse.class);
                return new ConcurrentOutcome(201, "", process(accepted.refundId()), probe);
            }
            return new ConcurrentOutcome(response.getStatus(), objectMapper.readTree(response.getContentAsString()).path("code").asText(), null, probe);
        } finally { observation.current.remove(); }
    }
    private record ConcurrentOutcome(int http, String code, TicketRefundResponse completed, Probe probe) { }

    @Test void at10FailedRequestAndANewRequestForTheSameTicketCannotBothSucceed() throws Exception {
        var ticket = sold.get(0);
        var old = accept(List.of(ticket));
        injectDatabaseFailure(old.refundId(), new java.sql.SQLNonTransientConnectionException("injected connection interruption", "08006"), 503);
        var current = accept(List.of(ticket));
        assertThat(old.requestId()).isNotEqualTo(current.requestId());
        var barrier = new CyclicBarrier(2);
        var a = async(() -> { barrier.await(10, TimeUnit.SECONDS); return processResponse(old.refundId()); });
        var b = async(() -> { barrier.await(10, TimeUnit.SECONDS); return processResponse(current.refundId()); });
        var oldResponse = a.get(15, TimeUnit.SECONDS); var currentResponse = b.get(15, TimeUnit.SECONDS);
        assertThat(oldResponse.getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(oldResponse.getContentAsString()).path("code").asText()).isEqualTo("REFUND_ALREADY_REQUESTED");
        assertThat(currentResponse.getStatus()).isEqualTo(200);
        assertSingleTicketRefund(ticket);
        evidence("day11-at10-competing-existing-requests", Map.of("failedRefundId", old.refundId(),
                "newRefundId", current.refundId(), "failedRetryHttp", oldResponse.getStatus(), "newProcessHttp", currentResponse.getStatus(),
                "successfulRefundAmount", successfulRefundAmount(ticket.getTicketId()), "resourcesRestored", 1));
    }

    private BigDecimal successfulRefundAmount(Long ticketId) {
        return jdbc.queryForObject("SELECT SUM(refund_amount) FROM ticket_refund_item WHERE ticket_id = ? AND status = 'SUCCEEDED'", BigDecimal.class, ticketId);
    }
    private void assertSingleTicketRefund(Ticket ticket) {
        assertResources(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_refund_item WHERE ticket_id = ? AND status = 'SUCCEEDED'", Integer.class, ticket.getTicketId())).isEqualTo(1);
        var expected = ticket.getPaidPrice().multiply(new BigDecimal("0.85")).setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(successfulRefundAmount(ticket.getTicketId())).isEqualByComparingTo(expected);
        assertThat(orders.findById(orderId).getStatus()).isEqualTo("PARTIALLY_REFUNDED");
    }

    @Test void partialRefundRestoresAggregatedZonesSeatsQuotaAndFrozenQuotes() throws Exception {
        var accepted = accept(List.of(sold.get(3), sold.get(0), sold.get(1)));
        var probe = new Probe(); observation.current.set(probe);
        TicketRefundResponse completed;
        try { completed = process(accepted.refundId()); } finally { observation.current.remove(); }
        assertSucceeded(accepted, completed);
        assertResources(3);
        assertThat(orders.findById(orderId).getStatus()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(completed.items().stream().map(item -> item.refundAmount()).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
                .isEqualByComparingTo(accepted.items().stream().map(item -> item.refundAmount()).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
        assertLockOrder(probe, accepted);
        evidence("day11-partial-refund", Map.of("accepted", accepted, "completed", completed,
                "lockStatements", probe.statements, "orderStatus", orders.findById(orderId).getStatus()));
    }

    @Test void repeatedProcessingAndApplicationReplayReturnTheOriginalResult() throws Exception {
        var input = applicationBody(List.of(sold.get(0), sold.get(2)));
        var accepted = acceptBody(input);
        var completed = process(accepted.refundId());
        var before = snapshot();
        assertThat(process(accepted.refundId())).isEqualTo(completed);
        assertThat(acceptBody(input)).isEqualTo(completed);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void successiveOneOneTwoRefundsDeriveSummaryFromRemainingValidTickets() throws Exception {
        process(accept(List.of(sold.get(0))).refundId());
        assertThat(orders.findById(orderId).getStatus()).isEqualTo("PARTIALLY_REFUNDED"); assertResources(1);
        process(accept(List.of(sold.get(1))).refundId());
        assertThat(orders.findById(orderId).getStatus()).isEqualTo("PARTIALLY_REFUNDED"); assertResources(2);
        process(accept(List.of(sold.get(3), sold.get(2))).refundId());
        assertThat(orders.findById(orderId).getStatus()).isEqualTo("REFUNDED"); assertResources(4);
    }

    @Test void acceptedRefundSucceedsAfterSessionStartAndLifecycleChangesWithoutRepricing() throws Exception {
        var accepted = accept(sold);
        var now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class);
        jdbc.update("UPDATE activity_session SET start_time = ?, end_time = ?, status = 'FINISHED' WHERE activity_session_id = ?",
                now.minusHours(2), now.minusHours(1), session.getActivitySessionId());
        jdbc.update("UPDATE activity SET status = 'FINISHED' WHERE activity_id = ?", activity.getActivityId());
        jdbc.update("UPDATE session_zone SET price = 99.99 WHERE session_id = ?", session.getActivitySessionId());
        assertSucceeded(accepted, process(accepted.refundId()));
        assertResources(4);
        assertThat(orders.findById(orderId).getStatus()).isEqualTo("REFUNDED");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void concurrentProcessingOfSameOrDifferentRequestsRestoresEachTicketOnce(boolean sameRequest) throws Exception {
        var first = accept(sameRequest ? sold : sold.subList(0, 2));
        var second = sameRequest ? first : accept(sold.subList(2, 4));
        var barrier = new CyclicBarrier(2);
        var a = async(() -> { barrier.await(10, TimeUnit.SECONDS); return process(first.refundId()); });
        var b = async(() -> { barrier.await(10, TimeUnit.SECONDS); return process(second.refundId()); });
        var resultA = a.get(15, TimeUnit.SECONDS); var resultB = b.get(15, TimeUnit.SECONDS);
        assertSucceeded(first, resultA); assertSucceeded(second, resultB);
        if (sameRequest) { assertThat(resultA).isEqualTo(resultB); }
        assertResources(4);
        assertThat(orders.findById(orderId).getStatus()).isEqualTo("REFUNDED");
    }

    @Test void refundAndPaymentOnDifferentOrdersShareQuotaThenZoneOrder() throws Exception {
        var accepted = accept(sold);
        Long pending = createOrder(List.of(seatMapper.findByZoneId(firstZone.getZoneId()).get(2)));
        var barrier = new CyclicBarrier(2);
        var refund = async(() -> { barrier.await(10, TimeUnit.SECONDS); return process(accepted.refundId()); });
        var payment = async(() -> {
            barrier.await(10, TimeUnit.SECONDS);
            return mvc.perform(post("/ticket-orders/{id}/pay", pending).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                    .andExpect(status().isOk()).andReturn();
        });
        assertSucceeded(accepted, refund.get(15, TimeUnit.SECONDS)); payment.get(15, TimeUnit.SECONDS);
        assertThat(purchases.findValidCount(buyer.getId(), session.getActivitySessionId())).isEqualTo(1);
        assertThat(zoneMapper.findById(firstZone.getZoneId()).getRemainsAmount()).isEqualTo(4);
        assertThat(zoneMapper.findById(secondZone.getZoneId()).getRemainsAmount()).isEqualTo(5);
        assertThat(tickets.findByOrderId(pending)).hasSize(1).allSatisfy(t -> assertThat(t.getStatus()).isEqualTo("VALID"));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void paymentAndRefundWaitOnQualificationGuardInBothCommitOrders(boolean refundHoldsFirst) throws Exception {
        var accepted = accept(sold);
        Long pending = createOrder(List.of(seatMapper.findByZoneId(firstZone.getZoneId()).get(2)));
        var held = gate(); var release = gate(); var attempting = gate();
        var holderId = new java.util.concurrent.atomic.AtomicLong();
        var waiterId = new java.util.concurrent.atomic.AtomicLong();
        var holderProbe = new Probe(); var waiterProbe = new Probe();
        holderProbe.afterStatement = id -> {
            if (id.endsWith("SessionZoneMapper.findByIdForUpdate") && holderId.compareAndSet(0, connectionId())) {
                held.countDown(); await(release);
            }
        };
        waiterProbe.beforeStatement = id -> {
            if (id.endsWith("QualificationGuardMapper.ensureExists")) {
                waiterId.set(connectionId()); attempting.countDown();
            }
        };
        var holder = async(() -> {
            observation.current.set(holderProbe);
            try { return refundHoldsFirst ? processResponse(accepted.refundId()) : payResponse(pending); }
            finally { observation.current.remove(); }
        });
        await(held);
        var waiter = async(() -> {
            observation.current.set(waiterProbe);
            try { return refundHoldsFirst ? payResponse(pending) : processResponse(accepted.refundId()); }
            finally { observation.current.remove(); }
        });
        try {
            await(attempting);
            var wait = awaitDatabaseLockWait(waiterId.get(), holderId.get(), "user_activity_qualification_guard");
            evidence("day11-payment-refund-" + (refundHoldsFirst ? "refund-first" : "payment-first"), wait);
        } finally { release.countDown(); }
        assertThat(holder.get(15, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
        assertThat(waiter.get(15, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
        assertQuotaBeforeZones(holderProbe); assertQuotaBeforeZones(waiterProbe);
        assertThat(purchases.findValidCount(buyer.getId(), session.getActivitySessionId())).isEqualTo(1);
        assertThat(zoneMapper.findById(firstZone.getZoneId()).getRemainsAmount()).isEqualTo(4);
        assertThat(zoneMapper.findById(secondZone.getZoneId()).getRemainsAmount()).isEqualTo(5);
    }

    private void assertQuotaBeforeZones(Probe probe) {
        int quota = probe.statements.indexOf("com.limitactivitysystem.ticketorder.mapper.UserSessionPurchaseMapper.findForUpdate");
        if (quota < 0) { quota = probe.statements.indexOf("com.limitactivitysystem.ticketorder.mapper.UserSessionPurchaseMapper.ensureExists"); }
        int guard = probe.statements.indexOf("com.limitactivitysystem.qualification.mapper.QualificationGuardMapper.lockVersion");
        assertThat(guard).isNotNegative().isLessThan(quota);
        assertThat(quota).isNotNegative().isLessThan(probe.statements.indexOf("com.limitactivitysystem.activity.mapper.SessionZoneMapper.findByIdForUpdate"));
        assertThat(probe.zoneIds).isSorted();
        int seat = probe.statements.indexOf("com.limitactivitysystem.activity.mapper.SessionSeatMapper.findByIdForUpdate");
        if (seat < 0) { seat = probe.statements.indexOf("com.limitactivitysystem.activity.mapper.SessionSeatMapper.occupySeat"); }
        assertThat(seat).isGreaterThan(probe.statements.lastIndexOf("com.limitactivitysystem.activity.mapper.SessionZoneMapper.findByIdForUpdate"));
        assertThat(probe.seatIds).isSorted(); assertThat(probe.occupiedSeatIds).isSorted();
    }

    @Test void processorWaitsOnTheOrderBeforeLockingTheRequest() throws Exception {
        var accepted = accept(sold);
        var locked = gate(); var release = gate(); var attempting = gate();
        var holderId = new java.util.concurrent.atomic.AtomicLong();
        var waiterId = new java.util.concurrent.atomic.AtomicLong();
        var holder = async(() -> tx(() -> {
            orders.findByIdForUpdate(orderId); holderId.set(connectionId()); locked.countDown(); await(release); return true;
        }));
        await(locked);
        var processor = async(() -> {
            var probe = new Probe();
            probe.beforeOrder = () -> { waiterId.set(connectionId()); attempting.countDown(); };
            observation.current.set(probe);
            try { return process(accepted.refundId()); } finally { observation.current.remove(); }
        });
        try {
            await(attempting);
            var wait = awaitDatabaseLockWait(waiterId.get(), holderId.get(), "ticket_order");
            assertThat(jdbc.queryForObject("SELECT status FROM ticket_refund_request WHERE refund_id = ?", String.class, accepted.refundId())).isEqualTo("PENDING");
            evidence("day11-order-lock-wait", wait);
        } finally { release.countDown(); }
        holder.get(15, TimeUnit.SECONDS); assertSucceeded(accepted, processor.get(15, TimeUnit.SECONDS));
    }

    @ParameterizedTest @CsvSource({"markProcessing,1", "markItemProcessing,2", "tryDecrease,1", "restoreStock,2",
            "releaseSold,2", "markRefunded,2", "markItemSucceeded,2", "updateRefundSummary,1", "markSucceeded,1"})
    void failureAfterRealWritesRollsBackEveryResourceAndCanRetryTheSameRefund(String point, int occurrence) throws Exception {
        var accepted = accept(sold); var before = snapshot();
        var probe = new Probe(); probe.point = point; probe.occurrence = occurrence; observation.current.set(probe);
        try { assertThatThrownBy(() -> asBuyer(() -> processing.process(accepted.refundId()))).hasRootCauseInstanceOf(InjectedProcessingFailure.class); }
        finally { observation.current.remove(); }
        assertThat(probe.injected).isTrue(); assertThat(snapshot()).isEqualTo(before);
        RefundInvariantAssertions.assertConsistent(jdbc);
        assertSucceeded(accepted, process(accepted.refundId())); assertResources(4);
    }

    static java.util.stream.Stream<Arguments> rollbackMatrix() {
        return java.util.stream.Stream.of(false, true).flatMap(full -> java.util.stream.Stream.of(
                Arguments.of(full, "quota-after", "tryDecrease", 1, false),
                Arguments.of(full, "zone-after", "restoreStock", 1, false),
                Arguments.of(full, "first-seat-after", "releaseSold", 1, false),
                Arguments.of(full, "first-ticket-after", "markRefunded", 1, false),
                Arguments.of(full, "all-tickets-after", "markRefunded", full ? 4 : 2, false),
                Arguments.of(full, "order-summary-before", "updateRefundSummary", 1, true),
                Arguments.of(full, "request-success-before", "markSucceeded", 1, true)));
    }

    @ParameterizedTest @MethodSource("rollbackMatrix")
    void sevenFailurePointsRollBackPartialAndFullRefunds(boolean full, String stage, String point, int occurrence, boolean beforeWrite) throws Exception {
        var accepted = accept(full ? sold : List.of(sold.get(1), sold.get(3)));
        var before = snapshot();
        var probe = new Probe(); probe.point = point; probe.occurrence = occurrence; probe.beforeWrite = beforeWrite;
        observation.current.set(probe);
        try { assertThatThrownBy(() -> asBuyer(() -> processing.process(accepted.refundId()))).hasRootCauseInstanceOf(InjectedProcessingFailure.class); }
        finally { observation.current.remove(); }
        assertThat(probe.injected).isTrue();
        assertThat(snapshot()).isEqualTo(before);
        assertResources(0);
        assertThat(orders.findById(orderId).getStatus()).isEqualTo("PAID");
        evidence("day11-rollback-" + (full ? "full-" : "partial-") + stage,
                Map.of("stage", stage, "beforeWrite", beforeWrite, "occurrence", occurrence,
                        "resourcesAndRefundRowsUnchanged", true, "ticketStatus", "VALID", "seatStatus", "SOLD",
                        "refundStatus", "PENDING", "orderStatus", "PAID"));
        assertSucceeded(accepted, process(accepted.refundId()));
        assertResources(full ? 4 : 2);
    }

    @ParameterizedTest @ValueSource(strings = {"CONNECTION", "LOCK_TIMEOUT", "UNEXPECTED_SQL"})
    void databaseFailuresRollBackThenRecordFailedInANewTransactionAndRetryTheOriginalQuote(String type) throws Exception {
        var accepted = accept(sold); var resourcesBefore = resourcesSnapshot();
        java.sql.SQLException failure = switch (type) {
            case "CONNECTION" -> new java.sql.SQLNonTransientConnectionException("injected connection interruption", "08006");
            case "LOCK_TIMEOUT" -> new java.sql.SQLException("Lock wait timeout exceeded; try restarting transaction", "HY000", 1205);
            case "UNEXPECTED_SQL" -> new java.sql.SQLSyntaxErrorException("injected syntax error", "42000", 1064);
            default -> throw new IllegalArgumentException(type);
        };
        String failureClass = switch (type) {
            case "CONNECTION" -> "DataAccessResourceFailureException";
            case "LOCK_TIMEOUT" -> "CannotAcquireLockException";
            default -> "BadSqlGrammarException";
        };
        var probe = new Probe(); probe.point = "tryDecrease"; probe.failure = failure;
        probe.beforeFailureRecording = () -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionName()).endsWith("TicketRefundFailureService.record");
            assertThat(resourcesSnapshot()).isEqualTo(resourcesBefore);
            assertThat(jdbc.queryForObject("SELECT status FROM ticket_refund_request WHERE refund_id = ?", String.class, accepted.refundId())).isEqualTo("PENDING");
            probe.observedRollbackBeforeRecording = true;
        };
        observation.current.set(probe);
        try { processRejected(accepted.refundId(), type.equals("UNEXPECTED_SQL") ? 500 : 503, "REFUND_TECHNICAL_FAILURE"); }
        finally { observation.current.remove(); }
        assertThat(probe.observedRollbackBeforeRecording).isTrue();
        assertThat(resourcesSnapshot()).isEqualTo(resourcesBefore);
        var failed = jdbc.queryForMap("SELECT status, result, processed_at FROM ticket_refund_request WHERE refund_id = ?", accepted.refundId());
        assertThat(failed.get("status")).isEqualTo("FAILED"); assertThat(failed.get("result")).isEqualTo("TECHNICAL_FAILURE");
        assertThat(jdbc.queryForList("SELECT status, failure_reason, claimed_ticket_id FROM ticket_refund_item WHERE refund_id = ?", accepted.refundId()))
                .hasSize(4).allSatisfy(item -> {
                    assertThat(item.get("status")).isEqualTo("FAILED"); assertThat(item.get("failure_reason")).isEqualTo(failureClass);
                    assertThat(item.get("claimed_ticket_id")).isNull();
                });
        RefundInvariantAssertions.assertConsistent(jdbc);
        var retried = process(accepted.refundId()); assertSucceeded(accepted, retried); assertResources(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_refund_request WHERE request_id = ?", Integer.class, accepted.requestId())).isEqualTo(1);
        evidence("day11-technical-failure-" + type.toLowerCase(Locale.ROOT), Map.of("http", type.equals("UNEXPECTED_SQL") ? 500 : 503,
                "failureClass", failureClass, "rollbackObservedBeforeIndependentFailureTransaction", true,
                "failedRequest", failed, "retry", retried));
    }

    @Test void delayedFailureRecorderCannotOverwriteAnotherProcessorsCommittedSuccess() throws Exception {
        var accepted = accept(sold); var resourcesBefore = resourcesSnapshot();
        var paused = gate(); var release = gate();
        var failedAttempt = async(() -> {
            var probe = new Probe(); probe.point = "tryDecrease";
            probe.failure = new java.sql.SQLException("Lock wait timeout exceeded; try restarting transaction", "HY000", 1205);
            probe.beforeFailureRecording = () -> {
                assertThat(resourcesSnapshot()).isEqualTo(resourcesBefore);
                paused.countDown(); await(release);
            };
            observation.current.set(probe);
            try { return processResponse(accepted.refundId()); } finally { observation.current.remove(); }
        });
        await(paused);
        TicketRefundResponse completed;
        try { completed = process(accepted.refundId()); } finally { release.countDown(); }
        assertThat(failedAttempt.get(15, TimeUnit.SECONDS).getStatus()).isEqualTo(503);
        var before = snapshot();
        assertThat(process(accepted.refundId())).isEqualTo(completed);
        assertThat(snapshot()).isEqualTo(before); assertResources(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_refund_item WHERE status = 'FAILED'", Integer.class)).isZero();
        evidence("day11-failure-recorder-success-race", Map.of("result", completed, "staleFailureDidNotOverwriteSuccess", true));
    }

    @Test void failureRecordingErrorsPreserveTheOriginalDatabaseFailureAndRollBackTheirOwnWrites() throws Exception {
        var accepted = accept(sold); var before = snapshot();
        var original = new java.sql.SQLException("original lock timeout", "HY000", 1205);
        var recordingFailure = new java.sql.SQLNonTransientConnectionException("failure recorder unavailable", "08006");
        var probe = new Probe(); probe.point = "tryDecrease"; probe.failure = original;
        probe.afterStatement = id -> { if (id.endsWith(".markItemFailed")) { throw new org.apache.ibatis.exceptions.PersistenceException(recordingFailure); } };
        observation.current.set(probe);
        try {
            assertThatThrownBy(() -> asBuyer(() -> execution.process(accepted.refundId()))).isInstanceOf(CannotAcquireLockException.class)
                    .hasRootCause(original).satisfies(error -> {
                        assertThat(error.getSuppressed()).hasSize(1);
                        assertThat(error.getSuppressed()[0]).isInstanceOf(DataAccessResourceFailureException.class).hasRootCause(recordingFailure);
                    });
        } finally { observation.current.remove(); }
        assertThat(snapshot()).isEqualTo(before);
    }

    private void injectDatabaseFailure(Long refundId, java.sql.SQLException failure, int http) throws Exception {
        var probe = new Probe(); probe.point = "tryDecrease"; probe.failure = failure; observation.current.set(probe);
        try { processRejected(refundId, http, "REFUND_TECHNICAL_FAILURE"); } finally { observation.current.remove(); }
        assertThat(probe.injected).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {"markProcessing", "markItemProcessing", "tryDecrease", "restoreStock",
            "releaseSold", "markRefunded", "markItemSucceeded", "updateRefundSummary", "markSucceeded"})
    void everyConditionalWriteRequiresExactlyOneAffectedRow(String point) throws Exception {
        var accepted = accept(sold); var before = snapshot();
        var probe = new Probe(); probe.point = point; probe.returnZero = true; observation.current.set(probe);
        try { processRejected(accepted.refundId(), 409, "REFUND_RESOURCE_CONFLICT"); }
        finally { observation.current.remove(); }
        assertThat(probe.injected).isTrue(); assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @CsvSource({"QUOTA,REFUND_RESOURCE_CONFLICT", "MISSING_QUOTA,REFUND_RESOURCE_CONFLICT",
            "ZONE_OVERFLOW,REFUND_RESOURCE_CONFLICT", "SEAT_AVAILABLE,REFUND_RESOURCE_CONFLICT",
            "WRONG_SEAT_ZONE,REFUND_RESOURCE_CONFLICT", "WRONG_ZONE_SESSION,REFUND_RESOURCE_CONFLICT",
            "MISSING_TICKET,REFUND_TICKET_NOT_FOUND", "REFUNDED,REFUND_TICKET_NOT_VALID",
            "OTHER_ORDER,REFUND_TICKET_NOT_IN_ORDER", "PRICE,REFUND_RESOURCE_CONFLICT", "ITEM_STATE,REFUND_RESOURCE_CONFLICT"})
    void corruptOrPreviouslyRefundedResourcesCannotBeRestored(String corruption, String code) throws Exception {
        var accepted = accept(sold); var ticket = sold.get(0);
        switch (corruption) {
            case "QUOTA" -> jdbc.update("UPDATE user_session_purchase SET valid_count = 1");
            case "MISSING_QUOTA" -> jdbc.update("DELETE FROM user_session_purchase");
            case "ZONE_OVERFLOW" -> jdbc.update("UPDATE session_zone SET remains_amount = total_amount WHERE zone_id = ?", firstZone.getZoneId());
            case "SEAT_AVAILABLE" -> jdbc.update("UPDATE session_seat SET allocation_status = 'AVAILABLE' WHERE seat_id = ?", ticket.getSeatId());
            case "WRONG_SEAT_ZONE" -> {
                Long originalZone = seatMapper.findById(ticket.getSeatId()).getZoneId();
                Long wrongZone = originalZone.equals(firstZone.getZoneId()) ? secondZone.getZoneId() : firstZone.getZoneId();
                jdbc.update("UPDATE session_seat SET row_no = 99, zone_id = ? WHERE seat_id = ?",
                        wrongZone, ticket.getSeatId());
            }
            case "WRONG_ZONE_SESSION" -> jdbc.update("UPDATE session_zone SET session_id = ? WHERE zone_id = ?", Long.MAX_VALUE, firstZone.getZoneId());
            case "MISSING_TICKET" -> jdbc.update("DELETE FROM ticket WHERE ticket_id = ?", ticket.getTicketId());
            case "REFUNDED" -> jdbc.update("UPDATE ticket SET status = 'REFUNDED', refunded_at = CURRENT_TIMESTAMP(3) WHERE ticket_id = ?", ticket.getTicketId());
            case "OTHER_ORDER" -> {
                var other = tx(() -> newOrder(session, buyer));
                jdbc.update("UPDATE ticket_order_item SET order_id = ? WHERE order_item_id = ?", other.getOrderId(), ticket.getOrderItemId());
            }
            case "PRICE" -> jdbc.update("UPDATE ticket SET paid_price = 1.00 WHERE ticket_id = ?", ticket.getTicketId());
            case "ITEM_STATE" -> jdbc.update("UPDATE ticket_refund_item SET status = 'PROCESSING' WHERE refund_id = ?", accepted.refundId());
            default -> throw new IllegalArgumentException(corruption);
        }
        var before = snapshot(); processRejected(accepted.refundId(), corruption.equals("MISSING_TICKET") ? 404 : 409, code);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(RefundInvariantAssertions.violations(jdbc)).isNotEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"PROCESSING", "FAILED"})
    void nonPendingStatesRequireAnExplicitRecoveryWorkflow(String state) throws Exception {
        var accepted = accept(sold);
        jdbc.update("UPDATE ticket_refund_request SET status = ? WHERE refund_id = ?", state, accepted.refundId());
        var before = snapshot(); processRejected(accepted.refundId(), 409, "REFUND_NOT_PENDING"); assertThat(snapshot()).isEqualTo(before);
    }

    @Test void processingRequiresAuthenticationAndBothRequestAndOrderOwnership() throws Exception {
        var accepted = accept(sold); var before = snapshot();
        mvc.perform(post("/ticket-refunds/{id}/process", accepted.refundId())).andExpect(status().isUnauthorized());
        mvc.perform(post("/ticket-refunds/{id}/process", accepted.refundId()).header(HttpHeaders.AUTHORIZATION, bearer(otherPublisher)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REFUND_ACCESS_DENIED"));
        assertThat(snapshot()).isEqualTo(before);
        jdbc.update("UPDATE ticket_order SET buyer_id = ? WHERE order_id = ?", otherPublisher.getId(), orderId);
        before = snapshot(); processRejected(accepted.refundId(), 403, "REFUND_ACCESS_DENIED"); assertThat(snapshot()).isEqualTo(before);
    }

    @Test void processRejectsMissingOrInvalidIdsAndAllClientSuppliedProcessingData() throws Exception {
        processRejected(Long.MAX_VALUE, 404, "REFUND_NOT_FOUND");
        processRejected(0L, 400, "INVALID_REFUND_REQUEST");
        var accepted = accept(sold); var before = snapshot();
        for (String body : List.of("{}", "null", "{\"refundAmount\":1}", "{\"ticketIds\":[1]}",
                "{\"feeRate\":0}", "{\"originalPrice\":1}", "{\"status\":\"SUCCEEDED\"}")) {
            mvc.perform(post("/ticket-refunds/{id}/process", accepted.refundId()).header(HttpHeaders.AUTHORIZATION, bearer(buyer))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REFUND_REQUEST"));
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void refundedSeatCanBeResoldAndReplayingTheOldRefundCannotReleaseItAgain() throws Exception {
        var original = sold.get(0);
        var accepted = accept(List.of(original));
        var completed = process(accepted.refundId());
        Long newOrder = createOrder(List.of(seatMapper.findById(original.getSeatId())));
        mvc.perform(post("/ticket-orders/{id}/pay", newOrder).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isOk());
        var replacement = tickets.findByOrderId(newOrder).get(0);
        assertThat(replacement.getSeatId()).isEqualTo(original.getSeatId());
        assertThat(replacement.getTicketId()).isNotEqualTo(original.getTicketId());
        assertThat(replacement.getStatus()).isEqualTo("VALID");
        assertThat(jdbc.queryForObject("SELECT active_seat_id FROM ticket WHERE ticket_id = ?", Long.class, original.getTicketId())).isNull();
        assertThat(jdbc.queryForObject("SELECT active_seat_id FROM ticket WHERE ticket_id = ?", Long.class, replacement.getTicketId())).isEqualTo(original.getSeatId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE seat_id = ? AND status = 'VALID'", Integer.class, original.getSeatId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ticket' AND index_name = 'uq_ticket_active_seat' AND non_unique = 0", Integer.class)).isEqualTo(1);
        assertThat(tickets.findByOrderId(orderId).get(0).getStatus()).isEqualTo("REFUNDED");
        var before = snapshot();
        assertThat(process(accepted.refundId())).isEqualTo(completed);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(seatMapper.findById(original.getSeatId()).getAllocationStatus()).isEqualTo("SOLD");
        assertThat(purchases.findValidCount(buyer.getId(), session.getActivitySessionId())).isEqualTo(4);
        evidence("day11-refunded-seat-resale", Map.of("oldTicketId", original.getTicketId(), "oldStatus", "REFUNDED",
                "newTicket", replacement, "oldActiveSeatIsNull", true, "validTicketsAtSeat", 1, "oldRefundReplayUnchanged", true));
    }

    @ParameterizedTest @CsvSource({"NO_ITEMS,REFUND_REQUEST_AMOUNT", "RESULT_TIME,SUCCEEDED_REFUND_RESULT", "ORDER_STATUS,ORDER_REFUND_SUMMARY"})
    void reconciliationDetectsBrokenRefundSummariesAndResults(String damage, String expected) throws Exception {
        var accepted = accept(sold); process(accepted.refundId());
        switch (damage) {
            case "NO_ITEMS" -> jdbc.update("DELETE FROM ticket_refund_item WHERE refund_id = ?", accepted.refundId());
            case "RESULT_TIME" -> jdbc.update("UPDATE ticket_refund_item SET processed_at = NULL WHERE refund_id = ?", accepted.refundId());
            case "ORDER_STATUS" -> jdbc.update("UPDATE ticket_order SET status = 'PAID' WHERE order_id = ?", orderId);
            default -> throw new IllegalArgumentException(damage);
        }
        assertThat(RefundInvariantAssertions.violations(jdbc).stream().map(row -> row.get("violation"))).contains(expected);
    }

    private Long createOrder(List<SessionSeat> selected) throws Exception {
        return createOrder(session.getActivitySessionId(), selected);
    }
    private Long createOrder(Long sessionId, List<SessionSeat> selected) throws Exception {
        var items = selected.stream().map(seat -> Map.of("zoneId", seat.getZoneId(), "allocationMode", "SELECT_SEAT",
                "requestedSeatId", seat.getSeatId(), "quantity", 1, "acceptNonAdjacent", false)).toList();
        var result = postJson("/ticket-orders", buyer, Map.of("requestId", UUID.randomUUID().toString(),
                "sessionId", sessionId, "items", items)).andExpect(status().isCreated()).andReturn().getResponse();
        return objectMapper.readTree(result.getContentAsString()).path("orderId").asLong();
    }
    private Map<String, Object> applicationBody(List<Ticket> selected) {
        return Map.of("requestId", UUID.randomUUID().toString(), "orderId", orderId,
                "ticketIds", selected.stream().map(Ticket::getTicketId).toList(), "reason", "USER_REQUEST");
    }
    private TicketRefundResponse accept(List<Ticket> selected) throws Exception { return acceptBody(applicationBody(selected)); }
    private TicketRefundResponse acceptBody(Map<String, Object> body) throws Exception {
        var result = postJson("/ticket-refunds", buyer, body).andExpect(status().isCreated()).andReturn().getResponse();
        return objectMapper.readValue(result.getContentAsString(), TicketRefundResponse.class);
    }
    private TicketRefundResponse process(Long id) throws Exception {
        var result = mvc.perform(post("/ticket-refunds/{id}/process", id).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isOk()).andReturn().getResponse();
        return objectMapper.readValue(result.getContentAsString(), TicketRefundResponse.class);
    }
    private org.springframework.mock.web.MockHttpServletResponse processResponse(Long id) throws Exception {
        return mvc.perform(post("/ticket-refunds/{id}/process", id).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andReturn().getResponse();
    }
    private org.springframework.mock.web.MockHttpServletResponse payResponse(Long id) throws Exception {
        return mvc.perform(post("/ticket-orders/{id}/pay", id).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andReturn().getResponse();
    }
    private <T> T asBuyer(java.util.concurrent.Callable<T> operation) throws Exception {
        var jwt = jwtDecoder.decode(JwtTestTokens.token(jwtEncoder, buyer.getId(), buyer.getRole()));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticationConverter.convert(jwt)); SecurityContextHolder.setContext(context);
        try { return operation.call(); } finally { SecurityContextHolder.clearContext(); }
    }
    private void processRejected(Long id, int http, String code) throws Exception {
        mvc.perform(post("/ticket-refunds/{id}/process", id).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().is(http)).andExpect(jsonPath("$.code").value(code));
    }
    private void assertSucceeded(TicketRefundResponse accepted, TicketRefundResponse completed) {
        assertThat(completed.status()).isEqualTo("SUCCEEDED"); assertThat(completed.refundId()).isEqualTo(accepted.refundId());
        assertThat(completed.requestedAt()).isEqualTo(accepted.requestedAt());
        assertThat(completed.processedAt()).isNotNull(); assertThat(completed.result()).isEqualTo("SIMULATED_REFUND_SUCCEEDED");
        assertThat(completed.items()).hasSize(accepted.items().size());
        for (int i = 0; i < completed.items().size(); i++) {
            var actual = completed.items().get(i); var original = accepted.items().get(i);
            assertThat(actual.status()).isEqualTo("SUCCEEDED"); assertThat(actual.failureReason()).isNull();
            assertThat(actual.ticketId()).isEqualTo(original.ticketId());
            assertThat(actual.originalPrice()).isEqualTo(original.originalPrice());
            assertThat(actual.feeRate()).isEqualTo(original.feeRate()); assertThat(actual.refundAmount()).isEqualTo(original.refundAmount());
            assertThat(actual.processedAt()).isEqualTo(completed.processedAt()); assertThat(actual.result()).isEqualTo(completed.result());
        }
    }
    private void assertResources(int refunded) {
        var current = tickets.findByOrderId(orderId);
        assertThat(current.stream().filter(t -> "REFUNDED".equals(t.getStatus())).count()).isEqualTo(refunded);
        assertThat(purchases.findValidCount(buyer.getId(), session.getActivitySessionId())).isEqualTo(4 - refunded);
        for (var ticket : current) {
            assertThat(seatMapper.findById(ticket.getSeatId()).getAllocationStatus())
                    .isEqualTo("REFUNDED".equals(ticket.getStatus()) ? "AVAILABLE" : "SOLD");
            var time = jdbc.queryForObject("SELECT refunded_at FROM ticket WHERE ticket_id = ?", LocalDateTime.class, ticket.getTicketId());
            if ("REFUNDED".equals(ticket.getStatus())) { assertThat(time).isNotNull(); }
            else { assertThat(time).isNull(); }
        }
        for (var zone : List.of(firstZone, secondZone)) {
            int valid = jdbc.queryForObject("SELECT COUNT(*) FROM ticket t JOIN ticket_order_item i ON i.order_item_id = t.order_item_id WHERE i.zone_id = ? AND t.status = 'VALID'", Integer.class, zone.getZoneId());
            assertThat(zoneMapper.findById(zone.getZoneId()).getRemainsAmount()).isEqualTo(5 - valid);
        }
        RefundInvariantAssertions.assertConsistent(jdbc);
    }
    private List<List<Map<String, Object>>> snapshot() {
        return List.of("ticket_order", "ticket_order_item", "ticket", "session_zone", "session_seat", "user_session_purchase",
                "ticket_refund_request", "ticket_refund_item").stream()
                .map(table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1")).toList();
    }
    private List<List<Map<String, Object>>> resourcesSnapshot() { return snapshot().subList(0, 6); }
    private void assertLockOrder(Probe probe, TicketRefundResponse accepted) {
        var methods = probe.statements.stream().map(id -> id.substring(id.lastIndexOf('.') + 1)).toList();
        assertThat(methods.indexOf("findByIdForUpdate")).isLessThan(methods.indexOf("findItemsForUpdate"));
        assertThat(methods.indexOf("findItemsForUpdate")).isLessThan(methods.indexOf("findForUpdate"));
        assertThat(probe.zoneIds).isSorted().doesNotHaveDuplicates(); assertThat(probe.seatIds).isSorted().doesNotHaveDuplicates();
        assertThat(probe.ticketIds).containsExactlyElementsOf(accepted.items().stream().map(item -> item.ticketId()).toList());
        assertThat(probe.statements.indexOf("com.limitactivitysystem.ticketorder.mapper.UserSessionPurchaseMapper.findForUpdate"))
                .isLessThan(probe.statements.indexOf("com.limitactivitysystem.activity.mapper.SessionZoneMapper.findByIdForUpdate"));
        assertThat(probe.statements.lastIndexOf("com.limitactivitysystem.activity.mapper.SessionZoneMapper.findByIdForUpdate"))
                .isLessThan(probe.statements.indexOf("com.limitactivitysystem.activity.mapper.SessionSeatMapper.findByIdForUpdate"));
        assertThat(probe.statements.lastIndexOf("com.limitactivitysystem.activity.mapper.SessionSeatMapper.findByIdForUpdate"))
                .isLessThan(probe.statements.indexOf("com.limitactivitysystem.refund.mapper.RefundTicketMapper.lockTicket"));
    }
    static class Probe {
        String point; int occurrence = 1; int seen; boolean injected; boolean returnZero;
        boolean beforeWrite; boolean observedRollbackBeforeRecording;
        Throwable failure = new InjectedProcessingFailure();
        Runnable beforeOrder = () -> { };
        Runnable beforeFailureRecording = () -> { };
        java.util.function.Consumer<String> beforeStatement = id -> { }, afterStatement = id -> { };
        final Map<String, Integer> writes = new HashMap<>();
        final List<String> statements = new ArrayList<>();
        final List<Long> zoneIds = new ArrayList<>(), seatIds = new ArrayList<>(), ticketIds = new ArrayList<>();
        final List<Long> occupiedSeatIds = new ArrayList<>();
    }
    static class InjectedProcessingFailure extends RuntimeException { }
    @Intercepts({@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
            @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})})
    static class ProcessingObservation implements Interceptor {
        final ThreadLocal<Probe> current = new ThreadLocal<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var probe = current.get(); String id = ((MappedStatement) invocation.getArgs()[0]).getId();
            if (probe != null) {
                probe.statements.add(id);
                probe.beforeStatement.accept(id);
                if (probe.injected && id.endsWith("TicketRefundMapper.findById")
                        && String.valueOf(TransactionSynchronizationManager.getCurrentTransactionName()).endsWith("TicketRefundFailureService.record")) {
                    probe.beforeFailureRecording.run();
                }
                if (id.endsWith("TicketOrderMapper.findByIdForUpdate")) { probe.beforeOrder.run(); }
                if (id.endsWith("SessionZoneMapper.findByIdForUpdate")) { probe.zoneIds.add((Long) ((Map<?, ?>) invocation.getArgs()[1]).get("zoneId")); }
                if (id.endsWith("SessionSeatMapper.findByIdForUpdate")) { probe.seatIds.add((Long) ((Map<?, ?>) invocation.getArgs()[1]).get("seatId")); }
                if (id.endsWith("SessionSeatMapper.occupySeat")) { probe.occupiedSeatIds.add((Long) ((Map<?, ?>) invocation.getArgs()[1]).get("seatId")); }
                if (id.endsWith("RefundTicketMapper.lockTicket")) { probe.ticketIds.add((Long) ((Map<?, ?>) invocation.getArgs()[1]).get("ticketId")); }
                if (probe.beforeWrite && id.endsWith("." + probe.point) && ++probe.seen == probe.occurrence) {
                    probe.injected = true; throw probe.failure;
                }
            }
            Object result = invocation.proceed();
            if (probe != null) {
                if (invocation.getMethod().getName().equals("update") && Integer.valueOf(1).equals(result)) {
                    probe.writes.merge(id.substring(id.lastIndexOf('.') + 1), 1, Integer::sum);
                }
                probe.afterStatement.accept(id);
            }
            if (probe != null && !probe.beforeWrite && id.endsWith("." + probe.point) && ++probe.seen == probe.occurrence) {
                assertThat(result).isEqualTo(1); probe.injected = true;
                if (probe.returnZero) { return 0; }
                throw probe.failure;
            }
            return result;
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class ObservationConfiguration {
        @Bean ProcessingObservation processingObservation() { return new ProcessingObservation(); }
    }
}
