package com.limitactivitysystem.activity.integration;

import com.limitactivitysystem.activity.model.*;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.refund.dto.TicketRefundResponse;
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
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.convention.TestBean;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.http.*;

@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import(TicketRefundApplicationMySqlIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class TicketRefundApplicationMySqlIntegrationTest extends PaymentResourceTestSupport {
    @Autowired private TicketMapper tickets;
    @Autowired private FailureObservation failures;
    @TestBean(name = "ticketRefundClock", methodName = "refundClock")
    private Clock authoritativeClock;
    private TestRefundClock clock;
    private Activity activity;
    private ActivitySession session;
    private SessionZone firstZone;
    private SessionZone secondZone;
    private Long orderId;
    private List<Ticket> sold;

    @BeforeEach void createFourActuallyPaidTicketsWithTwoPrices() throws Exception {
        clock = (TestRefundClock) authoritativeClock;
        clock.useSystemTime();
        var now = databaseNow();
        activity = insertDraft(publisher, "PAID");
        jdbc.update("UPDATE activity SET open_time = ?, close_time = ? WHERE activity_id = ?",
                now.minusDays(10), now.plusDays(1), activity.getActivityId());
        session = insertSession(activity);
        jdbc.update("UPDATE activity_session SET start_time = ?, end_time = ?, max_tickets_per_user = 10 WHERE activity_session_id = ?",
                now.plusDays(20), now.plusDays(20).plusHours(2), session.getActivitySessionId());
        firstZone = insertZone(session, "PAID", 5, 5); secondZone = insertZone(session, "PAID", 5, 5);
        jdbc.update("UPDATE session_zone SET price = 10.01 WHERE zone_id = ?", firstZone.getZoneId());
        jdbc.update("UPDATE session_zone SET price = 20.03 WHERE zone_id = ?", secondZone.getZoneId());
        publish(activity, publisher).andExpect(status().isNoContent());
        var seats = List.of(seatMapper.findByZoneId(firstZone.getZoneId()).get(0),
                seatMapper.findByZoneId(firstZone.getZoneId()).get(1),
                seatMapper.findByZoneId(secondZone.getZoneId()).get(0), seatMapper.findByZoneId(secondZone.getZoneId()).get(1));
        orderId = paidOrder(buyer, seats);
        sold = tickets.findByOrderId(orderId);
        assertThat(sold).hasSize(4);
    }

    @AfterEach void removeRefundFixtures() {
        failures.current.remove();
        jdbc.update("DELETE FROM ticket_refund_item"); jdbc.update("DELETE FROM ticket_refund_request");
    }

    @Test void partialRefundSnapshotsEachActualTicketPriceAndOnlyAcceptsPending() throws Exception {
        var before = resourcesSnapshot();
        var selected = List.of(sold.get(0).getTicketId(), sold.get(2).getTicketId());
        clock.resetReads();
        var result = accept(body("partial", orderId, selected), buyer);
        assertThat(clock.reads.get()).isEqualTo(1);
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.buyerId()).isEqualTo(buyer.getId());
        assertThat(result.requestedAt()).isNotNull(); assertThat(result.createdAt()).isNotNull();
        assertThat(result.items()).hasSize(2).allSatisfy(item -> {
            assertThat(item.status()).isEqualTo("PENDING"); assertThat(item.failureReason()).isNull();
            assertThat(item.feeRate()).isEqualByComparingTo("0.15");
        });
        assertThat(result.items().get(0).originalPrice()).isEqualByComparingTo("10.01");
        assertThat(result.items().get(0).refundAmount()).isEqualByComparingTo("8.51");
        assertThat(result.items().get(1).originalPrice()).isEqualByComparingTo("20.03");
        assertThat(result.items().get(1).refundAmount()).isEqualByComparingTo("17.03");
        assertThat(resourcesSnapshot()).isEqualTo(before);
        evidence("day10-refund-partial-pending", Map.of("response", result, "paidResourcesUnchanged", true));
    }

    @Test void allTicketsCanBeSubmittedAsOnePendingRequest() throws Exception {
        var before = resourcesSnapshot();
        var result = accept(body("all", orderId, sold.stream().map(Ticket::getTicketId).toList()), buyer);
        assertThat(result.items()).hasSize(4);
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(resourcesSnapshot()).isEqualTo(before);
    }

    @Test void at09FourDifferentPricesRefundOnlyTheSelectedTicketsAtTheirActualPrices() throws Exception {
        var now = databaseNow();
        var mixed = insertDraft(publisher, "PAID");
        jdbc.update("UPDATE activity SET open_time = ?, close_time = ? WHERE activity_id = ?",
                now.minusDays(10), now.plusDays(1), mixed.getActivityId());
        var mixedSession = insertSession(mixed);
        jdbc.update("UPDATE activity_session SET capacity = 4, max_tickets_per_user = 4, start_time = ?, end_time = ? WHERE activity_session_id = ?",
                now.plusDays(20), now.plusDays(20).plusHours(2), mixedSession.getActivitySessionId());
        var seats = new ArrayList<SessionSeat>();
        for (String price : List.of("200.00", "400.00", "800.00", "1200.00")) {
            var zone = insertZone(mixedSession, "PAID", 1, 1);
            jdbc.update("UPDATE session_zone SET price = ? WHERE zone_id = ?", new BigDecimal(price), zone.getZoneId());
            seats.add(seatMapper.findByZoneId(zone.getZoneId()).get(0));
        }
        publish(mixed, publisher).andExpect(status().isNoContent());
        Long mixedOrder = paidOrder(buyer, mixedSession.getActivitySessionId(), seats);
        var four = tickets.findByOrderId(mixedOrder);
        assertThat(four.stream().map(Ticket::getPaidPrice)).containsExactly(
                new BigDecimal("200.00"), new BigDecimal("400.00"), new BigDecimal("800.00"), new BigDecimal("1200.00"));
        var before = resourcesSnapshot();
        var selected = List.of(four.get(1).getTicketId(), four.get(3).getTicketId());
        var accepted = accept(body("at09-two-of-four", mixedOrder, selected), buyer);
        assertThat(accepted.status()).isEqualTo("PENDING");
        assertThat(accepted.items().stream().map(item -> item.ticketId())).containsExactlyElementsOf(selected);
        assertThat(accepted.items().stream().map(item -> item.originalPrice())).containsExactly(new BigDecimal("400.00"), new BigDecimal("1200.00"));
        assertThat(accepted.items().stream().map(item -> item.refundAmount())).containsExactly(new BigDecimal("340.00"), new BigDecimal("1020.00"));
        assertThat(accepted.items()).allSatisfy(item -> {
            assertThat(item.status()).isEqualTo("PENDING");
            assertThat(item.feeRate()).isEqualByComparingTo("0.15");
        });
        BigDecimal totalRefund = accepted.items().stream().map(item -> item.refundAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal persistedTotal = jdbc.queryForObject("SELECT SUM(refund_amount) FROM ticket_refund_item WHERE refund_id = ?",
                BigDecimal.class, accepted.refundId());
        assertThat(totalRefund).isEqualByComparingTo("1360.00");
        assertThat(persistedTotal).isEqualByComparingTo(totalRefund);
        assertThat(tickets.findByOrderId(mixedOrder)).allSatisfy(ticket -> assertThat(ticket.getStatus()).isEqualTo("VALID"));
        assertThat(orders.findById(mixedOrder).getStatus()).isEqualTo("PAID");
        assertThat(purchases.findValidCount(buyer.getId(), mixedSession.getActivitySessionId())).isEqualTo(4);
        seats.forEach(seat -> {
            assertThat(seatMapper.findById(seat.getSeatId()).getAllocationStatus()).isEqualTo("SOLD");
            assertThat(zoneMapper.findById(seat.getZoneId()).getRemainsAmount()).isZero();
        });
        assertThat(resourcesSnapshot()).isEqualTo(before);
        evidence("day10-at09-four-prices-pending", Map.of("allTickets", four, "selectedTicketIds", selected,
                "response", accepted, "totalRefund", totalRefund, "sumFromPersistedItems", persistedTotal,
                "totalStoredOnRequest", false, "paidResourcesUnchanged", true, "stage", "application acceptance"));
    }

    @Test void theServerClockSnapshotIsStoredAtMillisecondPrecision() throws Exception {
        var precise = databaseNow().plusSeconds(1).plusNanos(123_456);
        clock.set(precise);
        var accepted = accept(body("clock-precision", orderId, List.of(sold.get(0).getTicketId())), buyer);
        assertThat(accepted.requestedAt()).isEqualTo(precise.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        assertThat(accepted.requestedAt().getNano() % 1_000_000).isZero();
    }

    @Test void aRecentPaymentDoesNotRestartTheOpeningPromotion() throws Exception {
        var requested = databaseNow().plusSeconds(1);
        clock.set(requested);
        var paidAt = orders.findById(orderId).getPaidAt();
        var open = activityMapper.findById(activity.getActivityId()).getOpenTime();
        assertThat(requested).isBefore(paidAt.plusHours(48)).isAfter(open.plusHours(48));
        var accepted = accept(body("opening-not-payment", orderId, List.of(sold.get(0).getTicketId())), buyer);
        assertThat(accepted.items().get(0).feeRate()).isEqualByComparingTo("0.15");
    }

    @Test void aStartedSessionIsRejectedEvenDuringTheOpeningPromotion() throws Exception {
        var requested = databaseNow().plusSeconds(1);
        clock.set(requested);
        jdbc.update("UPDATE activity SET open_time = ? WHERE activity_id = ?", requested.minusHours(20), activity.getActivityId());
        jdbc.update("UPDATE activity_session SET start_time = ? WHERE activity_session_id = ?", requested.minusHours(1), session.getActivitySessionId());
        var before = allSnapshot();
        postJson("/ticket-refunds", buyer, body("started-promotion", orderId, List.of(sold.get(0).getTicketId())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_SESSION_STARTED"));
        assertThat(allSnapshot()).isEqualTo(before);
    }

    @Test void theOpeningPromotionAllowsAFullRefundWithinTenDaysOfStart() throws Exception {
        var requested = databaseNow().plusSeconds(1);
        clock.set(requested);
        jdbc.update("UPDATE activity SET open_time = ? WHERE activity_id = ?", requested.minusHours(20), activity.getActivityId());
        jdbc.update("UPDATE activity_session SET start_time = ? WHERE activity_session_id = ?", requested.plusDays(1), session.getActivitySessionId());
        var before = resourcesSnapshot();
        var accepted = accept(body("promotion-before-distance", orderId, List.of(sold.get(0).getTicketId())), buyer);
        assertThat(accepted.items().get(0).feeRate()).isEqualByComparingTo("0");
        assertThat(accepted.items().get(0).refundAmount()).isEqualByComparingTo("10.01");
        assertThat(resourcesSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest @CsvSource({"WINDOW_CLOSED,409,REFUND_WINDOW_CLOSED",
            "NOT_ALLOWED,409,REFUND_NOT_ALLOWED", "AMOUNT_ERROR,500,REFUND_AMOUNT_CALCULATION_ERROR"})
    void eligibilityAndCalculationFailuresHaveDistinctHttpCodesAndNoSideEffects(String scenario, int http, String code) throws Exception {
        var probe = new FailureProbe();
        if (scenario.equals("WINDOW_CLOSED")) {
            var start = sessionMapper.findById(session.getActivitySessionId()).getStartTime();
            clock.set(start.minusDays(10));
        } else if (scenario.equals("NOT_ALLOWED")) {
            clock.set(sold.get(0).getIssuedAt().minusNanos(1_000_000));
        } else { probe.invalidTicketPrice = true; failures.current.set(probe); }
        var before = allSnapshot();
        try {
            postJson("/ticket-refunds", buyer, body("error-" + scenario, orderId, List.of(sold.get(0).getTicketId())))
                    .andExpect(status().is(http)).andExpect(jsonPath("$.code").value(code));
        } finally { failures.current.remove(); }
        if (scenario.equals("AMOUNT_ERROR")) { assertThat(probe.injected).isTrue(); }
        assertThat(allSnapshot()).isEqualTo(before); assertNoApplications();
        evidence("day10-refund-error-" + scenario.toLowerCase(java.util.Locale.ROOT),
                Map.of("scenario", scenario, "httpStatus", http, "code", code, "applicationRows", 0, "paidResourcesUnchanged", true));
    }

    @Test void aPublisherMayRefundTheirOwnPaidTickets() throws Exception {
        Long ownedOrder = anotherOrder(publisher);
        var result = accept(body("publisher", ownedOrder,
                tickets.findByOrderId(ownedOrder).stream().map(Ticket::getTicketId).toList()), publisher);
        assertThat(result.buyerId()).isEqualTo(publisher.getId());
    }

    @ParameterizedTest
    @CsvSource({"OTHER_BUYER,403,REFUND_ACCESS_DENIED", "OTHER_ORDER,409,REFUND_TICKET_NOT_IN_ORDER",
            "FOREIGN_TICKET,409,REFUND_TICKET_NOT_IN_ORDER", "MISSING_TICKET,404,REFUND_TICKET_NOT_FOUND",
            "MISSING_ITEM,404,REFUND_TICKET_NOT_FOUND", "WRONG_ORDER_CHAIN,409,REFUND_TICKET_NOT_IN_ORDER",
            "REFUNDED,409,REFUND_TICKET_NOT_VALID", "PARTIAL_INVALID,404,REFUND_TICKET_NOT_FOUND",
            "UNPAID,409,REFUND_ORDER_NOT_PAID", "MISSING_ORDER,404,REFUND_ORDER_NOT_FOUND"})
    void ownershipMustFollowTicketThroughOrderItemToTheCurrentBuyersOrder(String change, int http, String code) throws Exception {
        var actor = buyer;
        Long requestedOrder = orderId;
        List<Long> selected = List.of(sold.get(0).getTicketId());
        switch (change) {
            case "OTHER_BUYER" -> actor = otherPublisher;
            case "OTHER_ORDER", "FOREIGN_TICKET" -> {
                Long otherOrder = anotherOrder(change.equals("FOREIGN_TICKET") ? otherPublisher : buyer);
                selected = List.of(tickets.findByOrderId(otherOrder).get(0).getTicketId());
            }
            case "MISSING_TICKET" -> selected = List.of(Long.MAX_VALUE);
            case "MISSING_ITEM" -> jdbc.update("UPDATE ticket SET order_item_id = ? WHERE ticket_id = ?", Long.MAX_VALUE, selected.get(0));
            case "WRONG_ORDER_CHAIN" -> {
                Long otherOrder = anotherOrder(buyer);
                jdbc.update("UPDATE ticket_order_item SET order_id = ? WHERE order_item_id = ?", otherOrder, sold.get(0).getOrderItemId());
            }
            case "REFUNDED" -> jdbc.update("UPDATE ticket SET status = 'REFUNDED', refunded_at = CURRENT_TIMESTAMP(3) WHERE ticket_id = ?", selected.get(0));
            case "PARTIAL_INVALID" -> selected = List.of(sold.get(0).getTicketId(), Long.MAX_VALUE);
            case "UNPAID" -> jdbc.update("UPDATE ticket_order SET status = 'PENDING_PAYMENT', paid_at = NULL WHERE order_id = ?", orderId);
            case "MISSING_ORDER" -> requestedOrder = Long.MAX_VALUE;
            default -> throw new IllegalArgumentException(change);
        }
        var before = allSnapshot();
        postJson("/ticket-refunds", actor, body(change, requestedOrder, selected))
                .andExpect(status().is(http)).andExpect(jsonPath("$.code").value(code));
        assertThat(allSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"buyerId", "refundRate", "feeRate", "refundAmount", "originalPrice", "requestedAt", "status"})
    void clientCannotSupplyServerOwnedFieldsEvenWithNull(String field) throws Exception {
        for (Object forged : Arrays.asList("forged", null)) {
            var input = body("forged", orderId, List.of(sold.get(0).getTicketId()));
            input.put(field, forged);
            var before = allSnapshot();
            postJson("/ticket-refunds", buyer, input).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REFUND_REQUEST"));
            assertThat(allSnapshot()).isEqualTo(before);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"requestId", "orderId", "ticketIds", "reason"})
    void requiredFieldsCannotBeOmitted(String field) throws Exception {
        var input = body("required", orderId, List.of(sold.get(0).getTicketId()));
        input.remove(field);
        postJson("/ticket-refunds", buyer, input).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REFUND_REQUEST"));
        assertNoApplications();
    }

    @ParameterizedTest @ValueSource(strings = {"EMPTY", "NULL", "ZERO", "NEGATIVE", "NULL_ELEMENT", "DUPLICATE", "BLANK_REASON", "LONG_REASON", "LONG_KEY"})
    void invalidListsAndTextAreRejectedAtomically(String change) throws Exception {
        var input = body("invalid", orderId, List.of(sold.get(0).getTicketId()));
        switch (change) {
            case "EMPTY" -> input.put("ticketIds", List.of());
            case "NULL" -> input.put("ticketIds", null);
            case "ZERO" -> input.put("ticketIds", List.of(0));
            case "NEGATIVE" -> input.put("ticketIds", List.of(-1));
            case "NULL_ELEMENT" -> input.put("ticketIds", Arrays.asList((Object) null));
            case "DUPLICATE" -> input.put("ticketIds", List.of(sold.get(0).getTicketId(), sold.get(0).getTicketId()));
            case "BLANK_REASON" -> input.put("reason", " ");
            case "LONG_REASON" -> input.put("reason", "x".repeat(501));
            case "LONG_KEY" -> input.put("requestId", "x".repeat(65));
            default -> throw new IllegalArgumentException(change);
        }
        postJson("/ticket-refunds", buyer, input).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REFUND_REQUEST"));
        assertNoApplications();
    }

    @Test void anonymousRequestsCannotReachTheApplicationService() throws Exception {
        mvc.perform(post("/ticket-refunds").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body("anon", orderId, List.of(sold.get(0).getTicketId())))))
                .andExpect(status().isUnauthorized());
        assertNoApplications();
    }

    @Test void replayAfterStartReturnsTheOriginalTimeAndAmountsWithoutRepricing() throws Exception {
        var input = body("replay", orderId, List.of(sold.get(2).getTicketId(), sold.get(0).getTicketId()));
        var accepted = accept(input, buyer);
        var before = allSnapshot();
        clock.set(databaseNow().plusDays(40));
        input.put("ticketIds", List.of(sold.get(0).getTicketId(), sold.get(2).getTicketId()));
        assertThat(accept(input, buyer)).isEqualTo(accepted);
        assertThat(allSnapshot()).isEqualTo(before);
        evidence("day10-refund-time-snapshot", Map.of("original", accepted, "replayAfterStartIsIdentical", true));
    }

    @Test void requestedAtIsFrozenBeforeWaitingForTheOrderLock() throws Exception {
        var start = jdbc.queryForObject("SELECT start_time FROM activity_session WHERE activity_session_id = ?",
                LocalDateTime.class, session.getActivitySessionId());
        var submitted = start.minusDays(14).minusNanos(1_000_000);
        var captured = gate(); var locked = gate(); var release = gate();
        clock.set(submitted);
        clock.onRead = captured::countDown;
        clock.resetReads();
        var before = resourcesSnapshot();
        var holder = async(() -> tx(() -> {
            orders.findByIdForUpdate(orderId); locked.countDown(); await(release); return true;
        }));
        await(locked);
        var application = async(() -> accept(body("clock-wait", orderId, List.of(sold.get(0).getTicketId())), buyer));
        try {
            await(captured);
            clock.set(start.minusDays(14));
        } finally { release.countDown(); }
        holder.get(10, TimeUnit.SECONDS);
        var accepted = application.get(10, TimeUnit.SECONDS);
        assertThat(clock.reads.get()).isEqualTo(1);
        assertThat(accepted.requestedAt()).isEqualTo(submitted);
        assertThat(accepted.items().get(0).feeRate()).isEqualByComparingTo("0.15");
        assertThat(resourcesSnapshot()).isEqualTo(before);
        evidence("day10-refund-before-lock-time", Map.of("submittedAt", submitted,
                "clockAfterSubmission", start.minusDays(14), "response", accepted, "serverClockReads", 1));
    }

    @Test void originalPriceComesFromTicketEvenIfTheZonePriceHasChanged() throws Exception {
        jdbc.update("UPDATE session_zone SET price = 99.99 WHERE zone_id = ?", firstZone.getZoneId());
        var response = accept(body("actual-paid-price", orderId, List.of(sold.get(0).getTicketId())), buyer);
        assertThat(response.items().get(0).originalPrice()).isEqualByComparingTo("10.01");
        assertThat(response.items().get(0).refundAmount()).isEqualByComparingTo("8.51");
    }

    @ParameterizedTest @ValueSource(strings = {"reason", "ticketIds", "orderId"})
    void idempotencyKeyMustRetainTheEntireRefundIntent(String field) throws Exception {
        var input = body("same-key", orderId, List.of(sold.get(0).getTicketId()));
        accept(input, buyer);
        if (field.equals("reason")) { input.put(field, "OTHER_REASON"); }
        if (field.equals("ticketIds")) { input.put(field, List.of(sold.get(1).getTicketId())); }
        if (field.equals("orderId")) { input.put(field, anotherOrder(buyer)); }
        var before = allSnapshot();
        postJson("/ticket-refunds", buyer, input).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_IDEMPOTENCY_CONFLICT"));
        assertThat(allSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void concurrentSameTicketApplicationsAreIdempotentOrOneIsRejected(boolean sameRequestId) throws Exception {
        var barrier = new CyclicBarrier(2);
        var first = async(() -> { barrier.await(10, TimeUnit.SECONDS); return postJson("/ticket-refunds", buyer,
                body("race", orderId, List.of(sold.get(0).getTicketId()))).andReturn().getResponse(); });
        var second = async(() -> { barrier.await(10, TimeUnit.SECONDS); return postJson("/ticket-refunds", buyer,
                body(sameRequestId ? "race" : "other-race", orderId, List.of(sold.get(0).getTicketId()))).andReturn().getResponse(); });
        var responses = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        assertThat(responses.stream().map(response -> response.getStatus())).containsExactlyInAnyOrder(201, sameRequestId ? 201 : 409);
        if (sameRequestId) {
            assertThat(objectMapper.readTree(responses.get(0).getContentAsString()).path("refundId"))
                    .isEqualTo(objectMapper.readTree(responses.get(1).getContentAsString()).path("refundId"));
        } else {
            var rejected = responses.stream().filter(response -> response.getStatus() == 409).findFirst().orElseThrow();
            assertThat(objectMapper.readTree(rejected.getContentAsString()).path("code").asText()).isEqualTo("REFUND_ALREADY_REQUESTED");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_refund_request", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_refund_item", Integer.class)).isEqualTo(1);
    }

    @Test void concurrentDifferentOrdersCannotReuseOneBuyersRequestId() throws Exception {
        Long otherOrder = anotherOrder(buyer);
        Long otherTicket = tickets.findByOrderId(otherOrder).get(0).getTicketId();
        var barrier = new CyclicBarrier(2);
        var first = async(() -> { barrier.await(10, TimeUnit.SECONDS); return postJson("/ticket-refunds", buyer,
                body("cross-order-race", orderId, List.of(sold.get(0).getTicketId()))).andReturn().getResponse(); });
        var second = async(() -> { barrier.await(10, TimeUnit.SECONDS); return postJson("/ticket-refunds", buyer,
                body("cross-order-race", otherOrder, List.of(otherTicket))).andReturn().getResponse(); });
        var responses = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        assertThat(responses.stream().map(response -> response.getStatus())).containsExactlyInAnyOrder(201, 409);
        var rejected = responses.stream().filter(response -> response.getStatus() == 409).findFirst().orElseThrow();
        assertThat(objectMapper.readTree(rejected.getContentAsString()).path("code").asText()).isEqualTo("REFUND_IDEMPOTENCY_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_refund_request", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_refund_item", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"insertRequest", "insertItem"})
    void failureAfterARealWriteRollsBackTheWholeApplication(String point) {
        var before = allSnapshot();
        var probe = new FailureProbe(); probe.point = point; failures.current.set(probe);
        try {
            assertThatThrownBy(() -> accept(body("failure", orderId, List.of(sold.get(0).getTicketId(), sold.get(2).getTicketId())), buyer))
                    .hasRootCauseInstanceOf(InjectedRefundFailure.class);
        } finally { failures.current.remove(); }
        assertThat(probe.injected).isTrue();
        assertThat(allSnapshot()).isEqualTo(before); assertNoApplications();
    }

    @ParameterizedTest @ValueSource(strings = {"PENDING", "PROCESSING", "SUCCEEDED"})
    void databaseClaimPreventsAnotherApplicationUntilFailure(String state) throws Exception {
        var input = body("claim", orderId, List.of(sold.get(0).getTicketId()));
        var accepted = accept(input, buyer);
        jdbc.update("UPDATE ticket_refund_item SET status = ? WHERE refund_id = ?", state, accepted.refundId());
        input.put("requestId", "next");
        postJson("/ticket-refunds", buyer, input).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_ALREADY_REQUESTED"));
        jdbc.update("UPDATE ticket_refund_item SET status = 'FAILED', failure_reason = 'TEST_FAILURE' WHERE refund_id = ?", accepted.refundId());
        jdbc.update("UPDATE ticket_refund_request SET status = 'FAILED' WHERE refund_id = ?", accepted.refundId());
        assertThat(accept(input, buyer).status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_refund_item", Integer.class)).isEqualTo(2);
    }

    @Test void schemaGuardsStatesAmountsAndHasNoPhysicalForeignKeys() throws Exception {
        var accepted = accept(body("schema", orderId, List.of(sold.get(0).getTicketId())), buyer);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'", Integer.class)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = DATABASE() AND constraint_type = 'FOREIGN KEY'", Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("UPDATE ticket_refund_request SET status = 'PAID' WHERE refund_id = ?", accepted.refundId()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_refund_request_status");
        assertThatThrownBy(() -> jdbc.update("UPDATE ticket_refund_item SET refund_amount = 1 WHERE refund_id = ?", accepted.refundId()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_refund_item_amount");
        assertThatThrownBy(() -> jdbc.update("UPDATE ticket_refund_item SET status = 'FAILED' WHERE refund_id = ?", accepted.refundId()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_refund_item_failure");
    }

    private Long anotherOrder(AppUser actor) throws Exception {
        return paidOrder(actor, List.of(seatMapper.findByZoneId(firstZone.getZoneId()).get(2)));
    }
    private Long paidOrder(AppUser actor, List<SessionSeat> selected) throws Exception {
        return paidOrder(actor, session.getActivitySessionId(), selected);
    }
    private Long paidOrder(AppUser actor, Long sessionId, List<SessionSeat> selected) throws Exception {
        var items = selected.stream().map(seat -> Map.of("zoneId", seat.getZoneId(), "allocationMode", "SELECT_SEAT",
                "requestedSeatId", seat.getSeatId(), "quantity", 1, "acceptNonAdjacent", false)).toList();
        var response = postJson("/ticket-orders", actor, Map.of("requestId", UUID.randomUUID().toString(),
                "sessionId", sessionId, "items", items)).andExpect(status().isCreated()).andReturn().getResponse();
        Long id = objectMapper.readTree(response.getContentAsString()).path("orderId").asLong();
        mvc.perform(post("/ticket-orders/{id}/pay", id).header(HttpHeaders.AUTHORIZATION, bearer(actor))).andExpect(status().isOk());
        return id;
    }
    private Map<String, Object> body(String key, Long order, List<Long> ids) {
        return new LinkedHashMap<>(Map.of("requestId", key, "orderId", order, "ticketIds", ids, "reason", "USER_REQUEST"));
    }
    private TicketRefundResponse accept(Map<String, Object> body, AppUser actor) throws Exception {
        var response = postJson("/ticket-refunds", actor, body).andExpect(status().isCreated()).andReturn().getResponse();
        return objectMapper.readValue(response.getContentAsString(), TicketRefundResponse.class);
    }
    private LocalDateTime databaseNow() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class); }
    private List<List<Map<String, Object>>> resourcesSnapshot() {
        return List.of("ticket_order", "ticket_order_item", "ticket", "session_zone", "session_seat", "user_session_purchase")
                .stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1")).toList();
    }
    private List<List<Map<String, Object>>> allSnapshot() {
        var result = new ArrayList<>(resourcesSnapshot());
        result.add(jdbc.queryForList("SELECT * FROM ticket_refund_request ORDER BY refund_id"));
        result.add(jdbc.queryForList("SELECT * FROM ticket_refund_item ORDER BY refund_item_id")); return result;
    }
    private void assertNoApplications() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_refund_request", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_refund_item", Integer.class)).isZero();
    }
    private static Clock refundClock() { return new TestRefundClock(); }
    static class TestRefundClock extends Clock {
        private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
        private volatile Clock delegate = Clock.system(ZONE);
        final java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        volatile Runnable onRead = () -> { };
        void useSystemTime() { delegate = Clock.system(ZONE); onRead = () -> { }; resetReads(); }
        void set(LocalDateTime value) { delegate = Clock.fixed(value.atZone(ZONE).toInstant(), ZONE); }
        void resetReads() { reads.set(0); }
        @Override public ZoneId getZone() { return ZONE; }
        @Override public Clock withZone(ZoneId zone) { return delegate.withZone(zone); }
        @Override public Instant instant() {
            Instant value = delegate.instant(); reads.incrementAndGet(); onRead.run(); return value;
        }
    }
    static class FailureProbe { String point; boolean injected; boolean invalidTicketPrice; }
    static class InjectedRefundFailure extends RuntimeException { }
    @Intercepts({@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
            @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})})
    static class FailureObservation implements Interceptor {
        final ThreadLocal<FailureProbe> current = new ThreadLocal<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed(); var probe = current.get();
            if (probe != null && probe.invalidTicketPrice
                    && ((MappedStatement) invocation.getArgs()[0]).getId().endsWith("RefundTicketMapper.lockTicket")) {
                // Simulate an invalid price returned from persistence without corrupting stored paid resources.
                ((Ticket) ((List<?>) result).get(0)).setPaidPrice(BigDecimal.ZERO);
                probe.injected = true;
            }
            if (probe != null && ((MappedStatement) invocation.getArgs()[0]).getId().endsWith("TicketRefundMapper." + probe.point)) {
                assertThat(result).isEqualTo(1); probe.injected = true; throw new InjectedRefundFailure();
            }
            return result;
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean IsolatedQualificationMySqlDataSource dataSource(DataSourceProperties properties) throws Exception {
            var source = new IsolatedQualificationMySqlDataSource(properties);
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V4__create_free_reservation_tables.sql"));
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V5__create_ticket_refund_tables.sql"));
                return source;
            } catch (Exception failure) { source.close(); throw failure; }
        }
        @Bean FailureObservation refundFailureObservation() { return new FailureObservation(); }
    }
}
