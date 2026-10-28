package com.limitactivitysystem.activity.integration;

import com.limitactivitysystem.activity.mapper.SessionSeatMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.ticketorder.dto.TicketPaymentResponse;
import com.limitactivitysystem.ticketorder.model.TicketOrder;
import com.limitactivitysystem.ticketorder.service.TicketPaymentService;
import com.limitactivitysystem.whitelist.exception.QualificationDeniedException;
import com.limitactivitysystem.whitelist.model.WhitelistScopeType;
import com.limitactivitysystem.whitelist.service.PublisherWhitelistService;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@AutoConfigureObservability
@Import(TicketPaymentMySqlIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class TicketPaymentMySqlIntegrationTest extends PaymentResourceTestSupport {
    @Autowired private TicketPaymentService payment;
    @Autowired private PublisherWhitelistService whitelist;
    @Autowired private PaymentObservation observer;
    @Autowired private io.micrometer.core.instrument.MeterRegistry metrics;
    @Autowired private org.apache.ibatis.session.SqlSessionFactory sqlSessions;
    private Activity activity;
    private ActivitySession session;
    private SessionZone zone;

    @BeforeEach void publishACompleteScopeWithAnOpenCreationWindow() throws Exception {
        LocalDateTime now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class);
        activity = insertDraft(publisher, "PAID");
        activity.setOpenTime(now.minusHours(1)); activity.setCloseTime(now.plusHours(1));
        jdbc.update("UPDATE activity SET open_time = ?, close_time = ? WHERE activity_id = ?",
                activity.getOpenTime(), activity.getCloseTime(), activity.getActivityId());
        session = insertSession(activity);
        jdbc.update("UPDATE activity_session SET max_tickets_per_user = 6 WHERE activity_session_id = ?", session.getActivitySessionId());
        zone = insertZone(session, "PAID", 10, 10);
        publish(activity, publisher).andExpect(status().isNoContent());
    }

    @AfterEach void removeWhitelistFixtures() {
        SecurityContextHolder.clearContext(); observer.current.remove();
        jdbc.update("DELETE FROM whitelist_member"); jdbc.update("DELETE FROM whitelist_config");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void eitherBuyerRoleCommitsTicketsAndEveryResourceTogether(boolean publisherRole) throws Exception {
        AppUser actor = publisherRole ? publisher : buyer;
        TicketOrder order = createThroughHttp(actor, 2, false, false);
        var observation = observation(order);
        complete(actor, order, observation);
        assertThat(observation.afterWrites).hasSize(2);
        assertThat(observation.afterWrites.get(1).validCount()).isEqualTo(2);
        assertThat(observation.afterWrites.get(1).remains()).isEqualTo(8);
        assertThat(observation.afterWrites.get(1).firstSeatStatus()).isEqualTo("SOLD");
        assertThat(observation.connections).hasSize(1);
        assertThat(observation.completion.get()).isEqualTo(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(observation.events.get(0)).endsWith("TicketOrderMapper.findByIdForUpdate");
        int firstQuota = firstEvent(observation, ".ensureExists");
        assertThat(firstEvent(observation, ".findByScopeForShare")).isBetween(1, firstQuota - 1);
        assertThat(firstEvent(observation, ".decreaseStock")).isGreaterThan(firstQuota);
        assertThat(orders.findById(order.getOrderId()).getStatus()).isEqualTo("PAID");
        assertThat(purchases.findValidCount(actor.getId(), session.getActivitySessionId())).isEqualTo(2);
        assertThat(zoneMapper.findById(zone.getZoneId()).getRemainsAmount()).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE status = 'VALID'", Long.class)).isEqualTo(2);
        assertThat(firstEvent(observation, "TicketMapper.insert")).isGreaterThan(firstEvent(observation, ".occupySeat"));
        assertThat(firstEvent(observation, ".markPaidIfPayable")).isGreaterThan(firstEvent(observation, "TicketMapper.insert"));
    }

    @Test void thePaymentBoundaryDoesNotJoinAnUnrelatedOuterTransaction() throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        tx(true, () -> { payAs(buyer, order.getOrderId(), observation(order)); return null; });
        assertThat(orders.findById(order.getOrderId()).getStatus()).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Long.class)).isEqualTo(2);
    }

    @Test void aValidOrderCreatedBeforeCloseCanReachResourceAllocationAfterTicketSalesClose() throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        jdbc.update("UPDATE ticket_order SET created_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 2 MINUTE) WHERE order_id = ?", order.getOrderId());
        jdbc.update("""
                UPDATE activity SET open_time = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 HOUR),
                    close_time = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE) WHERE activity_id = ?
                """, activity.getActivityId());
        var observed = observation(order);
        complete(buyer, order, observed);
        assertThat(observed.afterWrites).hasSize(2);
        var stored = orders.findById(order.getOrderId());
        assertThat(stored.getCreatedAt()).isBefore(activityMapper.findById(activity.getActivityId()).getCloseTime());
        assertThat(stored.getExpiresAt()).isAfter(jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class));
        assertThat(stored.getStatus()).isEqualTo("PAID");
        evidence("payment-after-sales-close", Map.of("createdBeforeClose", true, "orderNotExpired", true,
                "resourceStageReached", true, "committed", true, "ticketCount", 2));
    }

    @ParameterizedTest @ValueSource(strings = {"PARTIALLY_REFUNDED", "REFUNDED", "CLOSED"})
    void closedAndRefundedOrdersCannotStartANewPayment(String state) throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        if (state.equals("CLOSED")) {
            jdbc.update("UPDATE ticket_order SET status = 'CLOSED', closed_reason = 'TEST_CLOSED' WHERE order_id = ?", order.getOrderId());
        } else { jdbc.update("UPDATE ticket_order SET status = ?, paid_at = created_at WHERE order_id = ?", state, order.getOrderId()); }
        rejected(buyer, order, "ORDER_NOT_PENDING");
    }

    @ParameterizedTest @ValueSource(ints = {15, 16})
    void databaseExpiredOrdersCannotReserveAnyResource(int minutesAgo) throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        jdbc.update("UPDATE ticket_order SET created_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL ? MINUTE) WHERE order_id = ?",
                minutesAgo, order.getOrderId());
        rejected(buyer, order, "ORDER_EXPIRED");
    }

    @Test void missingOrderReturnsTheSpecificReason() {
        assertThatThrownBy(() -> payAs(buyer, Long.MAX_VALUE, new Observation()))
                .isInstanceOfSatisfying(TicketOrderException.class,
                        exception -> assertThat(exception.getErrorCode().name()).isEqualTo("ORDER_NOT_FOUND"));
    }

    @Test void anotherBuyerCannotDiscoverPaymentStateByAttemptingToPayTheOrder() throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        jdbc.update("UPDATE ticket_order SET status = 'CLOSED', closed_reason = 'TEST_CLOSED' WHERE order_id = ?", order.getOrderId());
        rejected(otherPublisher, order, "ORDER_ACCESS_DENIED");
    }

    @Test void aPreviouslyAuthenticatedBuyerIsRecheckedUnderTheAccountLock() throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        authenticate(buyer);
        jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", buyer.getId());
        var before = snapshot();
        try {
            assertThatThrownBy(() -> invoke(order.getOrderId(), observation(order))).isInstanceOfSatisfying(
                    TicketOrderException.class, exception -> assertThat(exception.getErrorCode().name()).isEqualTo("USER_DISABLED"));
        } finally { SecurityContextHolder.clearContext(); }
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"DRAFT", "CANCELLED", "FINISHED"})
    void activityMustStillPermitTrading(String state) throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        jdbc.update("UPDATE activity SET status = ? WHERE activity_id = ?", state, activity.getActivityId());
        rejected(buyer, order, "ACTIVITY_NOT_PUBLISHED");
    }

    @ParameterizedTest @ValueSource(strings = {"CANCELLED", "FINISHED", "STARTED"})
    void sessionMustStillBeScheduledAndNotStarted(String state) throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        if (state.equals("STARTED")) {
            jdbc.update("""
                    UPDATE activity_session SET start_time = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND),
                        end_time = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 HOUR) WHERE activity_session_id = ?
                    """, session.getActivitySessionId());
        } else { jdbc.update("UPDATE activity_session SET status = ? WHERE activity_session_id = ?", state, session.getActivitySessionId()); }
        rejected(buyer, order, "SESSION_NOT_AVAILABLE");
    }

    @ParameterizedTest @ValueSource(strings = {"ACTIVITY_MEMBER_REMOVED", "SESSION_EMPTY_OVERRIDE"})
    void qualificationAtCreationDoesNotAuthorizePaymentAfterAWhitelistChange(String change) throws Exception {
        asPublisher(() -> whitelist.enable(WhitelistScopeType.ACTIVITY, activity.getActivityId()));
        asPublisher(() -> whitelist.addMember(WhitelistScopeType.ACTIVITY, activity.getActivityId(), buyer.getId()));
        var order = createThroughHttp(buyer, 2, false, false);
        if (change.equals("ACTIVITY_MEMBER_REMOVED")) {
            asPublisher(() -> { whitelist.removeMember(WhitelistScopeType.ACTIVITY, activity.getActivityId(), buyer.getId()); return null; });
        } else { asPublisher(() -> whitelist.enable(WhitelistScopeType.SESSION, session.getActivitySessionId())); }
        var before = snapshot();
        var observed = observation(order);
        assertThatThrownBy(() -> payAs(buyer, order.getOrderId(), observed)).isInstanceOf(QualificationDeniedException.class);
        assertThat(observed.afterWrites).isEmpty();
        assertThat(observed.events).noneMatch(event -> event.endsWith(".ensureExists"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void eachAttemptReevaluatesTheCurrentSessionWhitelistInsteadOfCachingTheLastResult() throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        asPublisher(() -> whitelist.enable(WhitelistScopeType.SESSION, session.getActivitySessionId()));
        asPublisher(() -> whitelist.addMember(WhitelistScopeType.SESSION, session.getActivitySessionId(), buyer.getId()));
        // A rejected attempt grants no enduring authorization. Add then remove before the actual payment.
        asPublisher(() -> { whitelist.removeMember(WhitelistScopeType.SESSION, session.getActivitySessionId(), buyer.getId()); return null; });
        var before = snapshot();
        assertThatThrownBy(() -> payAs(buyer, order.getOrderId(), observation(order))).isInstanceOf(QualificationDeniedException.class);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void quotaFailureKeepsTheOriginalCounterAndOtherResources() throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        jdbc.update("INSERT INTO user_session_purchase (user_id, session_id, valid_count) VALUES (?, ?, 5)", buyer.getId(), session.getActivitySessionId());
        rejected(buyer, order, "PURCHASE_LIMIT_EXCEEDED");
    }

    @Test void stockFailureRollsBackQuotaInitialization() throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        jdbc.update("UPDATE session_zone SET remains_amount = 1 WHERE zone_id = ?", zone.getZoneId());
        var beforeResources = snapshot().subList(1, 6);
        assertThatThrownBy(() -> payAs(buyer, order.getOrderId(), observation(order))).isInstanceOfSatisfying(
                TicketOrderException.class, exception -> assertThat(exception.getErrorCode().name()).isEqualTo("INSUFFICIENT_INVENTORY"));
        assertThat(snapshot().subList(1, 6)).isEqualTo(beforeResources);
        var closed = orders.findById(order.getOrderId());
        assertThat(closed.getStatus()).isEqualTo("CLOSED");
        assertThat(closed.getClosedReason()).isEqualTo("INSUFFICIENT_INVENTORY");
    }

    @Test void anAlreadyOccupiedRequestedSeatIsNotReplaced() throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        long seat = seatMapper.findByZoneId(zone.getZoneId()).get(0).getSeatId();
        jdbc.update("UPDATE session_seat SET allocation_status = 'SOLD' WHERE seat_id = ?", seat);
        rejected(buyer, order, "SEAT_NOT_AVAILABLE");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void threeScatteredSeatsRespectTheSavedNonAdjacentChoice(boolean accepts) throws Exception {
        var order = createThroughHttp(buyer, 3, true, accepts);
        var seats = seatMapper.findByZoneId(zone.getZoneId());
        for (int index : List.of(2, 5, 8)) { jdbc.update("UPDATE session_seat SET allocation_status = 'SOLD' WHERE seat_id = ?", seats.get(index).getSeatId()); }
        jdbc.update("UPDATE session_zone SET remains_amount = 7 WHERE zone_id = ?", zone.getZoneId());
        if (!accepts) { rejected(buyer, order, "ADJACENT_SEATS_UNAVAILABLE"); }
        else {
            var observed = observation(order);
            complete(buyer, order, observed);
            assertThat(observed.afterWrites).hasSize(3);
            assertThat(orders.findById(order.getOrderId()).getStatus()).isEqualTo("PAID");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Long.class)).isEqualTo(3);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void corruptedRequestedSeatRelationsAreRejected(boolean missing) throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        long invalidId = missing ? Long.MAX_VALUE : insertZone(session, "PAID", 1, 1).getZoneId();
        if (!missing) { invalidId = seatMapper.findByZoneId(invalidId).get(0).getSeatId(); }
        long itemId = orderItems.findByOrderId(order.getOrderId()).get(0).getOrderItemId();
        jdbc.update("UPDATE ticket_order_item SET requested_seat_id = ? WHERE order_item_id = ?", invalidId, itemId);
        rejected(buyer, order, "INVALID_SEAT_RELATION");
    }

    @Test void incompletePaidConfigurationProducesABusinessReasonInsteadOfANullFailure() throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        jdbc.update("UPDATE activity_session SET seat_mode = NULL, max_tickets_per_user = NULL WHERE activity_session_id = ?", session.getActivitySessionId());
        rejected(buyer, order, "INVALID_SESSION_CONFIG");
    }

    @Test void quotaPlusTwoStockMinusTwoAndTheFirstSuccessfulSeatWriteAllRollbackOnTheSecondSeatFailure() throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        jdbc.update("INSERT INTO user_session_purchase (user_id, session_id, valid_count) VALUES (?, ?, 2)", buyer.getId(), session.getActivitySessionId());
        var targetSeats = seatMapper.findByZoneId(zone.getZoneId());
        var observed = observation(order); observed.beforeFirstSeat = gate(); observed.releaseFirstSeat = gate();
        var action = async(() -> { payAs(buyer, order.getOrderId(), observed); return null; });
        await(observed.beforeFirstSeat);
        // Independent raw writer forces the final conditional UPDATE to fail after the planned candidates were read.
        tx(() -> seatMapper.occupySeat(targetSeats.get(1).getSeatId(), zone.getZoneId()));
        observed.releaseFirstSeat.countDown();
        assertThatThrownBy(() -> action.get(10, TimeUnit.SECONDS)).isInstanceOfSatisfying(ExecutionException.class,
                exception -> assertThat(((TicketOrderException) exception.getCause()).getErrorCode().name()).isEqualTo("SEAT_NOT_AVAILABLE"));
        assertThat(observed.afterWrites).singleElement().satisfies(inside -> {
            assertThat(inside.validCount()).isEqualTo(4); assertThat(inside.remains()).isEqualTo(8);
            assertThat(inside.firstSeatStatus()).isEqualTo("SOLD");
        });
        assertThat(purchases.findValidCount(buyer.getId(), session.getActivitySessionId())).isEqualTo(2);
        assertThat(zoneMapper.findById(zone.getZoneId()).getRemainsAmount()).isEqualTo(10);
        assertThat(seatMapper.findById(targetSeats.get(0).getSeatId()).getAllocationStatus()).isEqualTo("AVAILABLE");
        assertThat(seatMapper.findById(targetSeats.get(1).getSeatId()).getAllocationStatus()).isEqualTo("SOLD");
        assertThat(observed.connections).hasSize(1);
        assertThat(observed.completion.get()).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(orders.findById(order.getOrderId()).getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Long.class)).isZero();
        evidence("ticket-payment-failure-rollback", Map.of("beforeValidCount", 2, "beforeRemains", 10,
                "insideAfterFirstSeat", observed.afterWrites.get(0), "afterValidCount", 2, "afterRemains", 10,
                "firstSeatAfterRollback", "AVAILABLE", "independentWriterSecondSeat", "SOLD",
                "transactionCompletion", "ROLLED_BACK", "samePaymentConnection", true));
    }

    @Test void realHttpPaymentsReturnTheOriginalTicketsWithoutRepeatingAnyResourceWrite() throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        var first = mvc.perform(post("/ticket-orders/{id}/pay", order.getOrderId()).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var paidSnapshot = snapshot();
        // Later loss of whitelist eligibility does not invalidate already issued Tickets.
        asPublisher(() -> whitelist.enable(WhitelistScopeType.SESSION, session.getActivitySessionId()));
        var second = mvc.perform(post("/ticket-orders/{id}/pay", order.getOrderId()).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(second)).isEqualTo(objectMapper.readTree(first));
        assertThat(snapshot()).isEqualTo(paidSnapshot);
    }

    @ParameterizedTest @CsvSource({".ensureExists,true", ".ensureExists,false", ".tryIncrease,true", ".tryIncrease,false",
            ".decreaseStock,true", ".decreaseStock,false", ".occupySeat,true", ".occupySeat,false",
            "TicketMapper.insert,true", "TicketMapper.insert,false", ".markPaidIfPayable,true", ".markPaidIfPayable,false"})
    void everyWriteStageRollsBackTheWholePaymentOnAnInjectedTechnicalFailure(String sqlSuffix, boolean before) throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        var original = snapshot(); var observed = observation(order);
        observed.failSuffix = sqlSuffix; observed.failBefore = before;
        assertThatThrownBy(() -> payAs(buyer, order.getOrderId(), observed))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).hasRootCauseInstanceOf(InjectedPaymentFailure.class);
        assertThat(snapshot()).isEqualTo(original);
        assertThat(observed.completions).containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(observed.events).noneMatch(event -> event.endsWith(".closePendingForInsufficientInventory"));
    }

    @ParameterizedTest @CsvSource({
            "AFTER_QUOTA,.tryIncrease,1,false,10,0,0",
            "AFTER_ZONE,.decreaseStock,1,false,8,0,0",
            "AFTER_FIRST_SEAT,.occupySeat,1,false,8,1,0",
            "AFTER_ALL_SEATS,.occupySeat,2,false,8,2,0",
            "AFTER_FIRST_TICKET,TicketMapper.insert,1,false,8,2,1",
            "AFTER_ALL_TICKETS,TicketMapper.insert,2,false,8,2,2",
            "BEFORE_PAID,.markPaidIfPayable,1,true,8,2,2"})
    void sevenAtomicityFailurePointsReconcileEveryTable(String point, String suffix, int occurrence, boolean before,
                                                       int insideRemains, int soldSeats, int issuedTickets) throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        jdbc.update("INSERT INTO user_session_purchase (user_id, session_id, valid_count) VALUES (?, ?, 0)", buyer.getId(), session.getActivitySessionId());
        var original = snapshot(); var observed = observation(order);
        observed.failSuffix = suffix; observed.failOccurrence = occurrence; observed.failBefore = before;
        assertThatThrownBy(() -> payAs(buyer, order.getOrderId(), observed))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).hasRootCauseInstanceOf(InjectedPaymentFailure.class);
        assertThat(observed.beforeFailure).isEqualTo(new FailureState(2, insideRemains, soldSeats, issuedTickets, "PENDING_PAYMENT"));
        assertThat(snapshot()).isEqualTo(original);
        assertThat(orders.findById(order.getOrderId()).getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(orders.findById(order.getOrderId()).getPaidAt()).isNull();
        assertThat(purchases.findValidCount(buyer.getId(), session.getActivitySessionId())).isZero();
        assertThat(zoneMapper.findById(zone.getZoneId()).getRemainsAmount()).isEqualTo(10);
        assertThat(seatMapper.findByZoneId(zone.getZoneId())).allSatisfy(seat -> assertThat(seat.getAllocationStatus()).isEqualTo("AVAILABLE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Long.class)).isZero();
        PaymentInvariantAssertions.assertConsistent(jdbc);
        evidence("payment-fault-matrix-" + point, Map.of("point", point, "insideBeforeFailure", observed.beforeFailure,
                "transactionCompletion", "ROLLED_BACK", "after", Map.of("orderStatus", "PENDING_PAYMENT", "validCount", 0,
                        "remains", 10, "availableSeats", 10, "tickets", 0, "invariantViolations", 0)));
    }

    @ParameterizedTest @ValueSource(strings = {"CONNECTION", "SQL", "UNKNOWN"})
    void technicalFailuresNeverBecomeOutOfStockClosures(String failureType) throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        var original = snapshot(); var observed = observation(order);
        observed.failSuffix = ".decreaseStock"; observed.technicalType = failureType;
        Class<? extends Exception> expected = switch (failureType) {
            case "CONNECTION" -> DataAccessResourceFailureException.class;
            case "SQL" -> BadSqlGrammarException.class;
            default -> InjectedPaymentFailure.class;
        };
        assertThatThrownBy(() -> payAs(buyer, order.getOrderId(), observed)).isInstanceOf(org.springframework.dao.DataAccessException.class)
                .satisfies(failure -> {
                    Throwable cause = failure;
                    while (cause != null && !expected.isInstance(cause)) { cause = cause.getCause(); }
                    assertThat(cause).isInstanceOf(expected);
                });
        assertThat(snapshot()).isEqualTo(original);
        assertThat(orders.findById(order.getOrderId()).getStatus()).isEqualTo("PENDING_PAYMENT");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void missingOrExtraPersistedTicketsCannotCommitAsPaid(boolean extraTicket) throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        var original = snapshot(); var observed = observation(order);
        observed.corruptTickets = extraTicket;
        observed.zeroSecondTicketInsert = !extraTicket;
        assertThatThrownBy(() -> payAs(buyer, order.getOrderId(), observed)).isInstanceOf(IllegalStateException.class);
        assertThat(snapshot()).isEqualTo(original);
    }

    @Test void aZeroRowFinalPaidUpdateRollsBackAllIssuedTickets() throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        var original = snapshot(); var observed = observation(order); observed.zeroPaidUpdate = true;
        assertThatThrownBy(() -> payAs(buyer, order.getOrderId(), observed)).isInstanceOfSatisfying(TicketOrderException.class,
                exception -> assertThat(exception.getErrorCode().name()).isEqualTo("ORDER_PAYMENT_CONFLICT"));
        assertThat(snapshot()).isEqualTo(original);
    }

    @Test void paidPriceComesFromOrderItemsEvenIfTheZoneDisplayPriceChanges() throws Exception {
        var order = createThroughHttp(buyer, 2, true, false);
        var originalPrices = orderItems.findByOrderId(order.getOrderId());
        jdbc.update("UPDATE session_zone SET price = 987.65 WHERE zone_id = ?", zone.getZoneId());
        var paid = payAs(buyer, order.getOrderId(), observation(order));
        assertThat(paid.totalAmount()).isEqualByComparingTo(order.getTotalAmount());
        assertThat(paid.tickets()).hasSize(2).allSatisfy(ticket ->
                assertThat(ticket.paidPrice()).isEqualByComparingTo(originalPrices.get(0).getUnitPrice()));
        assertThat(paid.tickets().stream().map(ticket -> ticket.paidPrice()).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
                .isEqualByComparingTo(order.getTotalAmount());
    }

    @Test void secondZoneStockFailureRollsBackTheFirstZoneAndQuotaBeforeAnIndependentClose() throws Exception {
        jdbc.update("UPDATE activity_session SET capacity = 20, seat_mode = 'AUTO_ASSIGN' WHERE activity_session_id = ?", session.getActivitySessionId());
        var secondZone = insertZone(session, "PAID", 4, 4);
        var result = postJson("/ticket-orders", buyer, Map.of("requestId", UUID.randomUUID().toString(), "sessionId", session.getActivitySessionId(),
                "items", List.of(Map.of("zoneId", secondZone.getZoneId(), "allocationMode", "AUTO_ASSIGN", "quantity", 1, "acceptNonAdjacent", false),
                Map.of("zoneId", zone.getZoneId(), "allocationMode", "AUTO_ASSIGN", "quantity", 1, "acceptNonAdjacent", false))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var order = orders.findById(objectMapper.readTree(result.getContentAsString()).path("orderId").asLong());
        jdbc.update("UPDATE session_zone SET remains_amount = 0 WHERE zone_id = ?", secondZone.getZoneId());
        var originalResources = snapshot().subList(1, 6); var observed = observation(order);
        assertThatThrownBy(() -> payAs(buyer, order.getOrderId(), observed)).isInstanceOfSatisfying(TicketOrderException.class,
                exception -> assertThat(exception.getErrorCode().name()).isEqualTo("INSUFFICIENT_INVENTORY"));
        assertThat(snapshot().subList(1, 6)).isEqualTo(originalResources);
        assertThat(observed.completions).containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK, TransactionSynchronization.STATUS_COMMITTED);
        assertThat(observed.transactions).hasSize(2);
        assertThat(orders.findById(order.getOrderId()).getClosedReason()).isEqualTo("INSUFFICIENT_INVENTORY");
        evidence("payment-stock-failure-independent-close", Map.of("paymentRolledBackBeforeClose", true,
                "firstZoneRestored", zoneMapper.findById(zone.getZoneId()).getRemainsAmount(), "secondZoneUnchanged", 0,
                "quotaRestored", purchases.findValidCount(buyer.getId(), session.getActivitySessionId()),
                "ticketCount", jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Long.class), "finalOrderState", "CLOSED"));
    }

    @Test void simultaneousHttpPaymentsOfTheSameOrderWaitThenReturnTheSameTickets() throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        var aObservation = observation(order); pauseAfter(aObservation, ".findByIdForUpdate");
        var a = async(() -> httpPay(buyer, order, aObservation)); await(aObservation.entered);
        var bObservation = observation(order); bObservation.notifySuffix = ".findByIdForUpdate"; bObservation.notified = gate();
        var b = async(() -> httpPay(buyer, order, bObservation)); await(bObservation.notified);
        var wait = awaitDatabaseLockWait(bObservation.connections.iterator().next(), aObservation.connections.iterator().next(), "ticket_order");
        aObservation.proceed.countDown();
        var first = a.get(10, TimeUnit.SECONDS); var second = b.get(10, TimeUnit.SECONDS);
        assertThat(first.status()).isEqualTo(200); assertThat(second.status()).isEqualTo(200);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(bObservation.events).noneMatch(event -> event.endsWith(".tryIncrease") || event.endsWith(".decreaseStock") || event.endsWith("TicketMapper.insert"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE status = 'VALID'", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order WHERE status = 'PAID'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE allocation_status = 'SOLD'", Long.class)).isEqualTo(2);
        assertThat(bObservation.afterWrites).isEmpty();
        assertThat(purchases.findValidCount(buyer.getId(), session.getActivitySessionId())).isEqualTo(2);
        assertThat(zoneMapper.findById(zone.getZoneId()).getRemainsAmount()).isEqualTo(8);
        PaymentInvariantAssertions.assertConsistent(jdbc);
        evidence("payment-same-order-idempotency", Map.of("lockWait", wait, "bothHttpStatus", List.of(first.status(), second.status()),
                "sameResponse", true, "tickets", 2, "quota", 2, "remains", 8));
    }

    @ParameterizedTest @ValueSource(strings = {"LAST_STOCK", "PURCHASE_LIMIT", "PURCHASE_LIMIT_FROM_ZERO", "SAME_SELECTED_SEAT"})
    void independentHttpTransactionsCannotOversellExceedQuotaOrSellOneSeatTwice(String scenario) throws Exception {
        boolean quota = scenario.startsWith("PURCHASE_LIMIT");
        boolean initialZero = scenario.equals("PURCHASE_LIMIT_FROM_ZERO");
        boolean selected = scenario.equals("SAME_SELECTED_SEAT");
        int quantity = quota ? initialZero ? 3 : 2 : 1;
        if (quota) {
            jdbc.update("UPDATE activity_session SET max_tickets_per_user = 4 WHERE activity_session_id = ?", session.getActivitySessionId());
            if (initialZero) {
                jdbc.update("INSERT INTO user_session_purchase (user_id, session_id, valid_count) VALUES (?, ?, 0)", buyer.getId(), session.getActivitySessionId());
            } else {
                var previous = createThroughHttp(buyer, 2, true, false);
                payAs(buyer, previous.getOrderId(), observation(previous));
            }
        }
        if (scenario.equals("LAST_STOCK")) {
            long lastSeat = seatMapper.findByZoneId(zone.getZoneId()).get(0).getSeatId();
            jdbc.update("DELETE FROM session_seat WHERE zone_id = ? AND seat_id <> ?", zone.getZoneId(), lastSeat);
            jdbc.update("UPDATE session_zone SET total_amount = 1, remains_amount = 1 WHERE zone_id = ?", zone.getZoneId());
        }
        var first = createThroughHttp(buyer, quantity, !selected, false);
        AppUser secondBuyer = quota ? buyer : otherPublisher;
        var second = createThroughHttp(secondBuyer, quantity, !selected, false);
        int previousTickets = quota && !initialZero ? 2 : 0;
        int previousPaidOrders = previousTickets == 0 ? 0 : 1;
        PaymentInvariantAssertions.assertConsistent(jdbc);
        assertThat(orderItems.findByOrderId(first.getOrderId()).stream().mapToInt(item -> item.getQuantity()).sum()).isEqualTo(quantity);
        assertThat(orderItems.findByOrderId(second.getOrderId()).stream().mapToInt(item -> item.getQuantity()).sum()).isEqualTo(quantity);
        assertThat(purchases.findValidCount(buyer.getId(), session.getActivitySessionId())).isEqualTo(quota && !initialZero ? 2 : 0);
        if (selected) {
            assertThat(orderItems.findByOrderId(first.getOrderId()).get(0).getRequestedSeatId())
                    .isEqualTo(orderItems.findByOrderId(second.getOrderId()).get(0).getRequestedSeatId());
        }
        var barrier = new CyclicBarrier(2);
        var firstObservation = observation(first); var secondObservation = observation(second);
        firstObservation.barrier = barrier; secondObservation.barrier = barrier;
        firstObservation.barrierSuffix = quota ? ".ensureExists" : "SessionZoneMapper.findByIdForUpdate";
        secondObservation.barrierSuffix = firstObservation.barrierSuffix;
        var a = async(() -> httpPay(buyer, first, firstObservation));
        var b = async(() -> httpPay(secondBuyer, second, secondObservation));
        var outcomes = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        assertThat(firstObservation.barrierConnection).isPositive().isNotEqualTo(secondObservation.barrierConnection);
        assertThat(secondObservation.barrierConnection).isPositive();
        assertThat(outcomes.stream().map(Outcome::status)).containsExactlyInAnyOrder(200, 409);
        String error = quota ? "PURCHASE_LIMIT_EXCEEDED" : selected ? "SEAT_NOT_AVAILABLE" : "INSUFFICIENT_INVENTORY";
        assertThat(outcomes.stream().filter(outcome -> outcome.status() == 409).findFirst().orElseThrow().body().path("code").asText()).isEqualTo(error);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order WHERE status = 'PAID'", Long.class)).isEqualTo(previousPaidOrders + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Long.class)).isEqualTo(previousTickets + quantity);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE status = 'VALID'", Long.class)).isEqualTo(previousTickets + quantity);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT seat_id) FROM ticket", Long.class)).isEqualTo(previousTickets + quantity);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE allocation_status = 'SOLD'", Long.class)).isEqualTo(previousTickets + quantity);
        int validCount = jdbc.queryForObject("SELECT SUM(valid_count) FROM user_session_purchase", Integer.class);
        assertThat(validCount).isEqualTo(quota ? initialZero ? 3 : 4 : 1);
        if (quota) { assertThat(validCount).isLessThanOrEqualTo(4); }
        int remains = zoneMapper.findById(zone.getZoneId()).getRemainsAmount();
        assertThat(remains).isEqualTo(scenario.equals("LAST_STOCK") ? 0 : 10 - previousTickets - quantity);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order WHERE status = 'CLOSED'", Long.class))
                .isEqualTo(scenario.equals("LAST_STOCK") ? 1 : 0);
        boolean firstWon = outcomes.get(0).status() == 200;
        var loser = firstWon ? second : first;
        var loserObservation = firstWon ? secondObservation : firstObservation;
        var winnerObservation = firstWon ? firstObservation : secondObservation;
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ticket t JOIN ticket_order_item i ON i.order_item_id = t.order_item_id
                WHERE i.order_id = ?
                """, Long.class, loser.getOrderId())).isZero();
        assertThat(orders.findById(loser.getOrderId()).getPaidAt()).isNull();
        assertThat(orders.findById(loser.getOrderId()).getStatus()).isEqualTo(scenario.equals("LAST_STOCK") ? "CLOSED" : "PENDING_PAYMENT");
        assertThat(loserObservation.completions.get(0)).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(winnerObservation.completions).containsExactly(TransactionSynchronization.STATUS_COMMITTED);
        if (!quota) {
            assertThat(purchases.findValidCount(loser.getBuyerId(), session.getActivitySessionId())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_session_purchase WHERE user_id = ? AND session_id = ?",
                    Long.class, loser.getBuyerId(), session.getActivitySessionId())).isZero();
        }
        if (selected) {
            long seatId = orderItems.findByOrderId(first.getOrderId()).get(0).getRequestedSeatId();
            assertThat(seatMapper.findById(seatId).getAllocationStatus()).isEqualTo("SOLD");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE seat_id = ? AND status = 'VALID'", Long.class, seatId)).isEqualTo(1);
        }
        PaymentInvariantAssertions.assertConsistent(jdbc);
        evidence("payment-core-" + scenario, Map.of("connections", List.of(firstObservation.barrierConnection, secondObservation.barrierConnection),
                "httpStatuses", outcomes.stream().map(Outcome::status).toList(), "loserCode", error,
                "initialValidCount", quota && !initialZero ? 2 : 0, "quantityPerOrder", quantity,
                "finalState", Map.of("paidOrders", previousPaidOrders + 1, "validTickets", previousTickets + quantity, "remains", remains,
                        "validCount", validCount, "soldSeats", previousTickets + quantity, "loserTickets", 0, "invariantViolations", 0,
                        "loserOrderStatus", orders.findById(loser.getOrderId()).getStatus(), "loserTransactionRolledBack", true)));
    }

    @ParameterizedTest @CsvSource({"ORDER,EXPIRY", "ZONE,EXPIRY", "ORDER,SESSION_START", "ZONE,SESSION_START",
            "BEFORE_PAID,EXPIRY", "AFTER_PAID,EXPIRY", "BEFORE_PAID,SESSION_START", "AFTER_PAID,SESSION_START"})
    void lockWaitsAndFinalUpdatesCannotCrossDatabasePaymentDeadlines(String stage, String boundaryType) throws Exception {
        var created = createThroughHttp(buyer, 2, false, false);
        boolean expiry = boundaryType.equals("EXPIRY");
        if (expiry) {
            jdbc.update("UPDATE ticket_order SET created_at = DATE_ADD(DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 15 MINUTE), INTERVAL 3 SECOND) WHERE order_id = ?", created.getOrderId());
        } else {
            jdbc.update("UPDATE activity_session SET start_time = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 3 SECOND) WHERE activity_session_id = ?", session.getActivitySessionId());
        }
        var order = orders.findById(created.getOrderId());
        LocalDateTime boundary = expiry ? order.getExpiresAt() : sessionMapper.findById(session.getActivitySessionId()).getStartTime();
        var original = snapshot(); var observed = observation(order);
        if (stage.equals("ORDER") || stage.equals("ZONE")) {
            var held = gate(); var release = gate(); var holderId = new java.util.concurrent.atomic.AtomicLong();
            var holder = async(() -> tx(() -> {
                holderId.set(connectionId());
                if (stage.equals("ORDER")) { orders.findByIdForUpdate(order.getOrderId()); }
                else { zoneMapper.findByIdForUpdate(zone.getZoneId()); }
                held.countDown(); await(release); return null;
            }));
            await(held);
            observed.notifySuffix = stage.equals("ORDER") ? "TicketOrderMapper.findByIdForUpdate" : "SessionZoneMapper.findByIdForUpdate";
            observed.notified = gate();
            var attempt = async(() -> httpPay(buyer, order, observed)); await(observed.notified);
            awaitDatabaseLockWait(observed.lastConnection, holderId.get(), stage.equals("ORDER") ? "ticket_order" : "session_zone");
            awaitDatabaseTime(boundary); release.countDown(); holder.get(10, TimeUnit.SECONDS);
            assertTimeRejection(attempt.get(10, TimeUnit.SECONDS), expiry);
        } else {
            observed.entered = gate(); observed.proceed = gate();
            if (stage.equals("BEFORE_PAID")) { observed.gateBeforeSuffix = ".markPaidIfPayable"; }
            else { observed.gateAfterSuffix = ".markPaidIfPayable"; }
            var attempt = async(() -> httpPay(buyer, order, observed)); await(observed.entered);
            assertThat(observed.ticketInserts).hasValue(2);
            awaitDatabaseTime(boundary); observed.proceed.countDown();
            assertTimeRejection(attempt.get(10, TimeUnit.SECONDS), expiry);
        }
        assertThat(snapshot()).isEqualTo(original);
        assertThat(observed.completions).containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK);
        PaymentInvariantAssertions.assertConsistent(jdbc);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void stockFailureClosureAndAnotherPaymentCannotOverwriteEachOthersFinalState(boolean paymentWins) throws Exception {
        jdbc.update("UPDATE activity_session SET max_tickets_per_user = 10 WHERE activity_session_id = ?", session.getActivitySessionId());
        var order = createThroughHttp(buyer, 2, true, false);
        var prior = createThroughHttp(otherPublisher, 9, true, false);
        payAs(otherPublisher, prior.getOrderId(), observation(prior));
        PaymentInvariantAssertions.assertConsistent(jdbc);
        var observed = observation(order); observed.gateBeforeClosure = true;
        observed.gapEntered = gate(); observed.gapProceed = gate();
        if (!paymentWins) { pauseAfter(observed, ".closePendingForInsufficientInventory"); }
        var originalAttempt = async(() -> httpPay(buyer, order, observed)); await(observed.gapEntered);
        assertThat(orders.findById(order.getOrderId()).getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(purchases.findValidCount(buyer.getId(), session.getActivitySessionId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Long.class)).isEqualTo(9);
        if (paymentWins) {
            // Assemble a coherent test-only replenishment during the rollback/closure gap; no edit API is added.
            jdbc.update("UPDATE activity_session SET capacity = 11 WHERE activity_session_id = ?", session.getActivitySessionId());
            jdbc.update("UPDATE session_zone SET total_amount = 11, remains_amount = 2 WHERE zone_id = ?", zone.getZoneId());
            jdbc.update("INSERT INTO session_seat (zone_id, row_no, seat_no, allocation_priority, allocation_status) VALUES (?, 1, 11, 11, 'AVAILABLE')", zone.getZoneId());
            var paid = httpPay(buyer, order, observation(order)); assertThat(paid.status()).isEqualTo(200);
            var paidSnapshot = snapshot(); observed.gapProceed.countDown();
            var recovered = originalAttempt.get(10, TimeUnit.SECONDS);
            assertThat(recovered.status()).isEqualTo(200); assertThat(recovered.body()).isEqualTo(paid.body());
            assertThat(snapshot()).isEqualTo(paidSnapshot);
            assertThat(orders.findById(order.getOrderId()).getClosedReason()).isNull();
            evidence("payment-wins-before-stock-failure-close", Map.of("originalPaymentRolledBack", true,
                    "concurrentPaymentCommitted", true, "failureRecorderReturnedExistingPaidResult", true,
                    "finalOrderState", "PAID", "newTicketCount", 2, "totalValidTickets", 11));
        } else {
            observed.gapProceed.countDown(); await(observed.entered);
            var competitorObservation = observation(order); competitorObservation.notifySuffix = "TicketOrderMapper.findByIdForUpdate";
            competitorObservation.notified = gate();
            var competitor = async(() -> httpPay(buyer, order, competitorObservation)); await(competitorObservation.notified);
            awaitDatabaseLockWait(competitorObservation.lastConnection, observed.lastConnection, "ticket_order");
            observed.proceed.countDown();
            assertThat(originalAttempt.get(10, TimeUnit.SECONDS).body().path("code").asText()).isEqualTo("INSUFFICIENT_INVENTORY");
            var rejected = competitor.get(10, TimeUnit.SECONDS);
            assertThat(rejected.status()).isEqualTo(409); assertThat(rejected.body().path("code").asText()).isEqualTo("ORDER_NOT_PENDING");
            assertThat(orders.findById(order.getOrderId()).getStatus()).isEqualTo("CLOSED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Long.class)).isEqualTo(9);
        }
        assertThat(observed.completions).containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK, TransactionSynchronization.STATUS_COMMITTED);
        PaymentInvariantAssertions.assertConsistent(jdbc);
    }

    @ParameterizedTest @CsvSource({"INVENTORY,ZONE_INVENTORY", "SOLD_WITHOUT_TICKET,SOLD_SEAT_TICKET",
            "VALID_WITHOUT_SOLD,VALID_TICKET_RELATION", "COUNTER,PURCHASE_COUNT", "MISSING_COUNTER,MISSING_PURCHASE_COUNTER",
            "MISSING_TICKET,PAID_ORDER_TICKET_COUNT", "WRONG_ITEM,PAID_ITEM_TICKET_COUNT", "PRICE,VALID_TICKET_RELATION"})
    void reconciliationSqlDetectsIndependentCrossTableCorruption(String corruption, String expectedViolation) throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        var paid = payAs(buyer, order.getOrderId(), observation(order));
        PaymentInvariantAssertions.assertConsistent(jdbc);
        long ticketId = paid.tickets().get(0).ticketId();
        switch (corruption) {
            case "INVENTORY" -> jdbc.update("UPDATE session_zone SET remains_amount = 9 WHERE zone_id = ?", zone.getZoneId());
            case "SOLD_WITHOUT_TICKET" -> jdbc.update("UPDATE session_seat SET allocation_status = 'SOLD' WHERE seat_id = ?", seatMapper.findByZoneId(zone.getZoneId()).get(2).getSeatId());
            case "VALID_WITHOUT_SOLD" -> jdbc.update("UPDATE session_seat SET allocation_status = 'AVAILABLE' WHERE seat_id = ?", paid.tickets().get(0).seatId());
            case "COUNTER" -> jdbc.update("UPDATE user_session_purchase SET valid_count = 3 WHERE user_id = ? AND session_id = ?", buyer.getId(), session.getActivitySessionId());
            case "MISSING_COUNTER" -> jdbc.update("DELETE FROM user_session_purchase WHERE user_id = ? AND session_id = ?", buyer.getId(), session.getActivitySessionId());
            case "MISSING_TICKET" -> jdbc.update("DELETE FROM ticket WHERE ticket_id = ?", ticketId);
            case "WRONG_ITEM" -> jdbc.update("UPDATE ticket SET order_item_id = ? WHERE ticket_id = ?", paid.tickets().get(1).orderItemId(), ticketId);
            case "PRICE" -> jdbc.update("UPDATE ticket SET paid_price = paid_price + 1 WHERE ticket_id = ?", ticketId);
            default -> throw new IllegalArgumentException(corruption);
        }
        assertThat(PaymentInvariantAssertions.violations(jdbc)).extracting(row -> row.get("violation")).contains(expectedViolation);
    }

    @Test void reconciliationAllowsPendingOrdersZeroCountersAndGiftAllocations() throws Exception {
        createThroughHttp(buyer, 2, true, false);
        jdbc.update("INSERT INTO user_session_purchase (user_id, session_id, valid_count) VALUES (?, ?, 0)", buyer.getId(), session.getActivitySessionId());
        jdbc.update("UPDATE activity_session SET capacity = 12 WHERE activity_session_id = ?", session.getActivitySessionId());
        var gift = insertZone(session, "GIFT", 2, 2);
        jdbc.update("UPDATE session_seat SET allocation_status = 'GIFT_ALLOCATED' WHERE seat_id = ?", seatMapper.findByZoneId(gift.getZoneId()).get(0).getSeatId());
        jdbc.update("UPDATE session_zone SET remains_amount = 1 WHERE zone_id = ?", gift.getZoneId());
        PaymentInvariantAssertions.assertConsistent(jdbc);
    }

    @Test void paymentSqlPlansAndDeclaredIndexesAreRecordedFromTheRealDatabase() throws Exception {
        var order = createThroughHttp(buyer, 2, false, false);
        long seatId = seatMapper.findByZoneId(zone.getZoneId()).get(0).getSeatId();
        jdbc.update("INSERT INTO user_session_purchase (user_id, session_id, valid_count) VALUES (?, ?, 0)", buyer.getId(), session.getActivitySessionId());
        var plans = new java.util.LinkedHashMap<String, Object>();
        plans.put("orderLock", jdbc.queryForList("EXPLAIN SELECT * FROM ticket_order WHERE order_id = ? FOR UPDATE", order.getOrderId()));
        plans.put("purchaseCounter", jdbc.queryForList("EXPLAIN UPDATE user_session_purchase SET valid_count = valid_count + 2 WHERE user_id = ? AND session_id = ? AND valid_count <= 4 - 2", buyer.getId(), session.getActivitySessionId()));
        plans.put("zoneStock", jdbc.queryForList("EXPLAIN UPDATE session_zone SET remains_amount = remains_amount - 2 WHERE zone_id = ? AND zone_type = 'PAID' AND remains_amount >= 2", zone.getZoneId()));
        plans.put("seatOccupancy", jdbc.queryForList("EXPLAIN UPDATE session_seat SET allocation_status = 'SOLD' WHERE seat_id = ? AND zone_id = ? AND allocation_status = 'AVAILABLE'", seatId, zone.getZoneId()));
        plans.put("availableCandidates", jdbc.queryForList("EXPLAIN SELECT seat_id FROM session_seat WHERE zone_id = ? AND allocation_status = 'AVAILABLE' ORDER BY allocation_priority, row_no, seat_no, seat_id", zone.getZoneId()));
        String paidSql = sqlSessions.getConfiguration().getMappedStatement("com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper.markPaidIfPayable")
                .getBoundSql(Map.of("orderId", order.getOrderId())).getSql();
        plans.put("finalPaidUpdate", jdbc.queryForList("EXPLAIN " + paidSql, order.getOrderId()));
        var ticketIndexes = jdbc.queryForList("SHOW INDEX FROM ticket").stream().map(row -> row.get("Key_name")).distinct().toList();
        assertThat(ticketIndexes).contains("PRIMARY", "uq_ticket_active_seat", "idx_ticket_item");
        plans.put("ticketIndexes", ticketIndexes);
        evidence("payment-sql-index-plans", plans);
    }

    @Test void realPaymentRequestsExposeMetricsAndCorrelatedSafeLogs() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(com.limitactivitysystem.observability.PaymentTelemetry.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
            var order = createThroughHttp(buyer, 2, true, false);
            double successes = metrics.get("payment.success").counter().count();
            double denied = metrics.get("payment.failure").tag("reason", "QUALIFICATION_DENIED").counter().count();
            long attempts = metrics.get("payment.duration").timer().count();
            var first = mvc.perform(post("/ticket-orders/{id}/pay", order.getOrderId()).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                    .andExpect(status().isOk()).andReturn().getResponse();
            mvc.perform(post("/ticket-orders/{id}/pay", order.getOrderId()).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                    .andExpect(status().isOk());
            var futureOrder = createThroughHttp(buyer, 1, true, false);
            asPublisher(() -> whitelist.enable(WhitelistScopeType.SESSION, session.getActivitySessionId()));
            mvc.perform(post("/ticket-orders/{id}/pay", futureOrder.getOrderId()).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                    .andExpect(status().isForbidden());
            assertThat(metrics.get("payment.success").counter().count()).isEqualTo(successes + 2);
            assertThat(metrics.get("payment.failure").tag("reason", "QUALIFICATION_DENIED").counter().count()).isEqualTo(denied + 1);
            assertThat(metrics.get("payment.duration").timer().count()).isEqualTo(attempts + 3);
            assertThat(logs.list).hasSize(3);
            assertThat(logs.list.get(0).getFormattedMessage()).contains("requestId=" + first.getHeader("X-Request-ID"),
                    "userId=" + buyer.getId(), "orderId=" + order.getOrderId(), "paymentResult=SUCCESS", "failureReason=NONE", "durationMs=");
            assertThat(logs.list.get(2).getFormattedMessage()).contains("orderId=" + futureOrder.getOrderId(), "paymentResult=FAILURE", "failureReason=QUALIFICATION_DENIED");
            assertThat(logs.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("Bearer ", "eyJ", "password", "passwordHash");
                assertThat(event.getThrowableProxy()).isNull();
            });
            for (String metric : List.of("payment.success", "payment.failure", "payment.duration")) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/metrics/{metric}", metric)
                        .header(HttpHeaders.AUTHORIZATION, bearer(buyer))).andExpect(status().isOk());
            }
            PaymentInvariantAssertions.assertConsistent(jdbc);
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    private void awaitDatabaseTime(LocalDateTime boundary) {
        long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class).isBefore(boundary)) {
            if (System.nanoTime() >= timeout) { throw new AssertionError("Database deadline was not reached"); }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }
    private void assertTimeRejection(Outcome result, boolean expiry) {
        assertThat(result.status()).isEqualTo(409);
        assertThat(result.body().path("code").asText()).isEqualTo(expiry ? "ORDER_EXPIRED" : "SESSION_NOT_AVAILABLE");
    }

    private TicketOrder createThroughHttp(AppUser actor, int quantity, boolean automatic, boolean accepts) throws Exception {
        jdbc.update("UPDATE activity_session SET seat_mode = ? WHERE activity_session_id = ?", automatic ? "AUTO_ASSIGN" : "SELECT_SEAT", session.getActivitySessionId());
        List<Map<String, Object>> requested = new ArrayList<>();
        if (automatic) { requested.add(Map.of("zoneId", zone.getZoneId(), "allocationMode", "AUTO_ASSIGN", "quantity", quantity, "acceptNonAdjacent", accepts)); }
        else {
            var configured = seatMapper.findByZoneId(zone.getZoneId());
            for (int index = 0; index < quantity; index++) { requested.add(Map.of("zoneId", zone.getZoneId(), "allocationMode", "SELECT_SEAT",
                    "requestedSeatId", configured.get(index).getSeatId(), "quantity", 1, "acceptNonAdjacent", false)); }
        }
        var result = postJson("/ticket-orders", actor, Map.of("requestId", UUID.randomUUID().toString(),
                        "sessionId", session.getActivitySessionId(), "items", requested))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return orders.findById(objectMapper.readTree(result.getContentAsString()).path("orderId").asLong());
    }
    private void authenticate(AppUser actor) {
        var token = jwtDecoder.decode(JwtTestTokens.token(jwtEncoder, actor.getId(), actor.getRole()));
        var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(authenticationConverter.convert(token));
        SecurityContextHolder.setContext(context);
    }
    private TicketPaymentResponse payAs(AppUser actor, Long orderId, Observation observed) {
        authenticate(actor);
        try { return invoke(orderId, observed); } finally { SecurityContextHolder.clearContext(); }
    }
    private TicketPaymentResponse invoke(Long orderId, Observation observed) {
        observer.current.set(observed);
        try { return payment.pay(orderId); } finally { observer.current.remove(); }
    }
    private void complete(AppUser actor, TicketOrder order, Observation observed) {
        payAs(actor, order.getOrderId(), observed);
    }
    private void rejected(AppUser actor, TicketOrder order, String code) {
        var before = snapshot();
        assertThatThrownBy(() -> payAs(actor, order.getOrderId(), observation(order))).isInstanceOfSatisfying(
                TicketOrderException.class, exception -> assertThat(exception.getErrorCode().name()).isEqualTo(code));
        assertThat(snapshot()).isEqualTo(before);
    }
    private Observation observation(TicketOrder order) {
        var observed = new Observation(); observed.userId = order.getBuyerId(); observed.sessionId = order.getSessionId();
        observed.zoneId = zone.getZoneId(); observed.firstSeatId = seatMapper.findByZoneId(zone.getZoneId()).get(0).getSeatId();
        return observed;
    }
    private List<List<Map<String, Object>>> snapshot() {
        return List.of("ticket_order", "ticket_order_item", "user_session_purchase", "session_zone", "session_seat", "ticket")
                .stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1")).toList();
    }
    private int firstEvent(Observation observation, String suffix) {
        for (int index = 0; index < observation.events.size(); index++) { if (observation.events.get(index).endsWith(suffix)) { return index; } }
        throw new AssertionError("Missing observed SQL step: " + suffix);
    }

    private Outcome httpPay(AppUser actor, TicketOrder order, Observation observed) throws Exception {
        observer.current.set(observed);
        try {
            var response = mvc.perform(post("/ticket-orders/{id}/pay", order.getOrderId()).header(HttpHeaders.AUTHORIZATION, bearer(actor)))
                    .andReturn().getResponse();
            return new Outcome(response.getStatus(), objectMapper.readTree(response.getContentAsString()));
        } finally { observer.current.remove(); }
    }
    private void pauseAfter(Observation observed, String suffix) {
        observed.gateAfterSuffix = suffix; observed.entered = gate(); observed.proceed = gate();
    }
    record Outcome(int status, com.fasterxml.jackson.databind.JsonNode body) { }
    static class InjectedPaymentFailure extends RuntimeException { }

    record ResourceObservation(int validCount, int remains, String firstSeatStatus) { }
    record FailureState(int validCount, int remains, int soldSeats, int tickets, String orderStatus) { }
    static class Observation {
        Long userId; Long sessionId; Long zoneId; Long firstSeatId;
        final Set<Long> connections = ConcurrentHashMap.newKeySet();
        volatile long lastConnection;
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<ResourceObservation> afterWrites = new CopyOnWriteArrayList<>();
        final AtomicInteger completion = new AtomicInteger(-1);
        final List<Integer> completions = new CopyOnWriteArrayList<>();
        final Set<Object> transactions = ConcurrentHashMap.newKeySet();
        CountDownLatch beforeFirstSeat; CountDownLatch releaseFirstSeat;
        String failSuffix; boolean failBefore; String technicalType;
        int failOccurrence = 1; final AtomicInteger failureMatches = new AtomicInteger();
        FailureState beforeFailure;
        boolean zeroSecondTicketInsert; boolean corruptTickets; boolean zeroPaidUpdate;
        final AtomicInteger ticketInserts = new AtomicInteger();
        String gateBeforeSuffix; String gateAfterSuffix; CountDownLatch entered; CountDownLatch proceed;
        String notifySuffix; CountDownLatch notified;
        CyclicBarrier barrier; String barrierSuffix; volatile long barrierConnection;
        boolean gateBeforeClosure; final AtomicInteger orderLocks = new AtomicInteger();
        CountDownLatch gapEntered; CountDownLatch gapProceed;
    }
    @Intercepts({@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
            @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})})
    static class PaymentObservation implements Interceptor {
        final ThreadLocal<Observation> current = new ThreadLocal<>();
        private final JdbcTemplate jdbc;
        PaymentObservation(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            Observation observation = current.get();
            if (observation == null || !TransactionSynchronizationManager.isActualTransactionActive()) { return invocation.proceed(); }
            String id = ((MappedStatement) invocation.getArgs()[0]).getId(); observation.events.add(id);
            var connection = ((Executor) invocation.getTarget()).getTransaction().getConnection();
            assertThat(connection.getAutoCommit()).isFalse();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            try (var statement = connection.createStatement(); var row = statement.executeQuery("SELECT CONNECTION_ID()")) {
                row.next(); observation.lastConnection = row.getLong(1); observation.connections.add(observation.lastConnection);
            }
            if (observation.transactions.add(TransactionSynchronizationManager.getResource(jdbc.getDataSource()))) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) { observation.completion.set(status); observation.completions.add(status); }
                });
            }
            if (observation.notifySuffix != null && id.endsWith(observation.notifySuffix)) { observation.notified.countDown(); }
            if (id.endsWith("TicketOrderMapper.findByIdForUpdate") && observation.orderLocks.incrementAndGet() == 2 && observation.gateBeforeClosure) {
                assertThat(observation.completions).containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK);
                observation.gapEntered.countDown(); await(observation.gapProceed);
            }
            if (observation.barrierSuffix != null && id.endsWith(observation.barrierSuffix)) {
                observation.barrierConnection = observation.lastConnection;
                observation.barrier.await(10, TimeUnit.SECONDS); observation.barrierSuffix = null;
            }
            if (observation.gateBeforeSuffix != null && id.endsWith(observation.gateBeforeSuffix)) {
                observation.gateBeforeSuffix = null; observation.entered.countDown(); await(observation.proceed);
            }
            boolean failNow = observation.failSuffix != null && id.endsWith(observation.failSuffix)
                    && observation.failureMatches.incrementAndGet() == observation.failOccurrence;
            if (observation.failBefore && failNow) { fail(observation); }
            boolean insertTicket = id.endsWith("TicketMapper.insert");
            int insertNumber = insertTicket ? observation.ticketInserts.incrementAndGet() : 0;
            if (insertNumber == 2 && observation.zeroSecondTicketInsert || observation.zeroPaidUpdate && id.endsWith(".markPaidIfPayable")) { return 0; }
            boolean occupy = id.equals(SessionSeatMapper.class.getName() + ".occupySeat");
            if (occupy && observation.beforeFirstSeat != null) {
                observation.beforeFirstSeat.countDown(); await(observation.releaseFirstSeat); observation.beforeFirstSeat = null;
            }
            Object result = invocation.proceed();
            if (insertNumber == 2 && observation.corruptTickets) {
                var ticket = (com.limitactivitysystem.ticketorder.model.Ticket) invocation.getArgs()[1];
                jdbc.update("INSERT INTO ticket (order_item_id, seat_id, paid_price, status) VALUES (?, ?, ?, 'VALID')",
                        ticket.getOrderItemId(), Long.MAX_VALUE, ticket.getPaidPrice());
            }
            if (occupy && Integer.valueOf(1).equals(result)) {
                observation.afterWrites.add(new ResourceObservation(
                        jdbc.queryForObject("SELECT valid_count FROM user_session_purchase WHERE user_id = ? AND session_id = ?",
                                Integer.class, observation.userId, observation.sessionId),
                        jdbc.queryForObject("SELECT remains_amount FROM session_zone WHERE zone_id = ?", Integer.class, observation.zoneId),
                        jdbc.queryForObject("SELECT allocation_status FROM session_seat WHERE seat_id = ?", String.class, observation.firstSeatId)));
            }
            if (observation.gateAfterSuffix != null && id.endsWith(observation.gateAfterSuffix)) {
                observation.gateAfterSuffix = null; observation.entered.countDown(); await(observation.proceed);
            }
            if (!observation.failBefore && failNow) { fail(observation); }
            return result;
        }
        private void fail(Observation observation) {
            observation.beforeFailure = new FailureState(
                    jdbc.queryForObject("SELECT COALESCE((SELECT valid_count FROM user_session_purchase WHERE user_id = ? AND session_id = ?), 0)", Integer.class, observation.userId, observation.sessionId),
                    jdbc.queryForObject("SELECT remains_amount FROM session_zone WHERE zone_id = ?", Integer.class, observation.zoneId),
                    jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE allocation_status = 'SOLD'", Integer.class),
                    jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Integer.class),
                    jdbc.queryForObject("SELECT status FROM ticket_order WHERE buyer_id = ? ORDER BY order_id DESC LIMIT 1", String.class, observation.userId));
            if ("CONNECTION".equals(observation.technicalType)) { throw new DataAccessResourceFailureException("Injected connection failure"); }
            if ("SQL".equals(observation.technicalType)) { jdbc.update("UPDATE __missing_payment_test_table SET value = 1"); }
            throw new InjectedPaymentFailure();
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean IsolatedQualificationMySqlDataSource dataSource(DataSourceProperties properties) throws Exception { return new IsolatedQualificationMySqlDataSource(properties); }
        @Bean PaymentObservation paymentObservation(JdbcTemplate jdbc) { return new PaymentObservation(jdbc); }
    }
}
