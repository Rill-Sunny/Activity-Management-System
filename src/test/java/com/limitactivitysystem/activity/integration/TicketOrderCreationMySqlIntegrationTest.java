package com.limitactivitysystem.activity.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.ticketorder.dto.CreateTicketOrderRequest;
import com.limitactivitysystem.ticketorder.mapper.DatabaseTimeMapper;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderItemMapper;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import com.limitactivitysystem.ticketorder.model.TicketOrderItem;
import com.limitactivitysystem.ticketorder.service.TicketOrderService;
import com.limitactivitysystem.ticketorder.service.TicketOrderQueryService;
import com.limitactivitysystem.whitelist.model.WhitelistScopeType;
import com.limitactivitysystem.whitelist.service.PublisherWhitelistService;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.limitactivitysystem.whitelist.model.WhitelistScopeType.ACTIVITY;
import static com.limitactivitysystem.whitelist.model.WhitelistScopeType.SESSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import(TicketOrderCreationMySqlIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class TicketOrderCreationMySqlIntegrationTest extends ActivityMySqlTestSupport {
    @Autowired private DatabaseTimeMapper time;
    @Autowired private TicketOrderMapper orders;
    @Autowired private TicketOrderItemMapper items;
    @Autowired private TicketOrderService service;
    @Autowired private TicketOrderQueryService queries;
    @Autowired private PublisherWhitelistService whitelist;
    @Autowired private OrderWriteFailure failure;
    private Activity activity;
    private ActivitySession session;
    private SessionZone firstZone;
    private SessionZone secondZone;

    @BeforeEach
    void publishAnOpenPaidActivity() throws Exception {
        failure.reset();
        LocalDateTime now = time.currentTime();
        activity = insertDraft(publisher, "PAID");
        activity.setOpenTime(now.minusHours(1));
        activity.setCloseTime(now.plusHours(1));
        jdbc.update("UPDATE activity SET open_time = ?, close_time = ? WHERE activity_id = ?",
                activity.getOpenTime(), activity.getCloseTime(), activity.getActivityId());
        session = insertSession(activity);
        jdbc.update("""
                UPDATE activity_session SET start_time = ?, end_time = ?, max_tickets_per_user = 6,
                    seat_mode = 'AUTO_ASSIGN' WHERE activity_session_id = ?
                """, now.plusHours(2), now.plusHours(3), session.getActivitySessionId());
        firstZone = insertZone(session, "PAID", 6, 6);
        secondZone = insertZone(session, "PAID", 4, 4);
        jdbc.update("UPDATE session_zone SET price = 0.10 WHERE zone_id = ?", firstZone.getZoneId());
        jdbc.update("UPDATE session_zone SET price = 0.20 WHERE zone_id = ?", secondZone.getZoneId());
        publish(activity, publisher).andExpect(status().isNoContent());
    }

    @AfterEach
    void clearOnlyTheOwnedSchemaBeforeParentFixtures() {
        failure.reset();
        jdbc.update("DELETE oe FROM outbox_event oe JOIN ticket_order o ON o.order_id = oe.aggregate_id");
        jdbc.update("DELETE FROM ticket_order_item");
        jdbc.update("DELETE FROM ticket_order");
        jdbc.update("DELETE FROM user_session_purchase");
        jdbc.update("DELETE FROM whitelist_member");
        jdbc.update("DELETE FROM whitelist_config");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createsForTheTrustedBuyerForEitherConsumerRole(boolean publisherRole) throws Exception {
        var actor = publisherRole ? publisher : buyer;
        var before = inventorySnapshot();
        JsonNode response = createAs(actor, request(auto(firstZone, 1, true)));
        var stored = orders.findById(response.path("orderId").asLong());
        assertThat(stored.getBuyerId()).isEqualTo(actor.getId());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox_event
                WHERE event_type = 'ORDER_EXPIRED' AND aggregate_id = ? AND status = 'PENDING'
                """, Long.class, stored.getOrderId())).isEqualTo(1);
        assertThat(stored.getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(stored.getCreatedAt()).isAfterOrEqualTo(activity.getOpenTime()).isBefore(activity.getCloseTime());
        assertThat(stored.getExpiresAt()).isEqualTo(stored.getCreatedAt().plusMinutes(15));
        assertThat(stored.getPaidAt()).isNull();
        assertThat(inventorySnapshot()).isEqualTo(before);
    }

    @Test
    void snapshotsDatabasePricesAndComputesAnExactDecimalTotalAcrossZones() throws Exception {
        var before = inventorySnapshot();
        JsonNode response = createAs(buyer, request(auto(firstZone, 2, true), auto(secondZone, 3, false)));
        long orderId = response.path("orderId").asLong();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order_item WHERE order_id = ?", Long.class, orderId))
                .isEqualTo(2);
        assertThat(orders.findById(orderId).getTotalAmount()).isEqualByComparingTo("0.80");
        assertThat(response.path("totalAmount").decimalValue()).isEqualByComparingTo("0.80");
        assertThat(items.findByOrderId(orderId)).extracting(item -> item.getUnitPrice().toPlainString())
                .containsExactly("0.10", "0.20");
        assertThat(items.findByOrderId(orderId)).extracting(item -> item.getQuantity()).containsExactly(2, 3);
        assertThat(inventorySnapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void autoAssignmentPreservesPreferenceWithoutChoosingSeats(boolean acceptNonAdjacent) throws Exception {
        var before = inventorySnapshot();
        JsonNode response = createAs(buyer, request(auto(firstZone, 2, acceptNonAdjacent)));
        var stored = items.findByOrderId(response.path("orderId").asLong()).get(0);
        assertThat(stored.getRequestedSeatId()).isNull();
        assertThat(stored.getAcceptNonAdjacent()).isEqualTo(acceptNonAdjacent);
        assertThat(response.path("items").get(0).path("requestedSeatId").isNull()).isTrue();
        assertThat(inventorySnapshot()).isEqualTo(before);
    }

    @Test
    void selectedSeatingStoresIntentWithoutCheckingOrTakingSeatAvailability() throws Exception {
        selectSeatMode();
        long seatId = seatMapper.findByZoneId(firstZone.getZoneId()).get(0).getSeatId();
        jdbc.update("UPDATE session_seat SET allocation_status = 'SOLD' WHERE seat_id = ?", seatId);
        var before = inventorySnapshot();
        JsonNode response = createAs(buyer, request(selected(firstZone, seatId)));
        assertThat(items.findByOrderId(response.path("orderId").asLong()).get(0).getRequestedSeatId()).isEqualTo(seatId);
        assertThat(inventorySnapshot()).isEqualTo(before);
    }

    enum Rule { PUBLIC, ACTIVITY_MEMBER, ACTIVITY_NONMEMBER, EMPTY_ACTIVITY,
        SESSION_NONMEMBER, SESSION_MEMBER, EMPTY_SESSION, EMPTY_SESSION_WITH_PUBLIC_ACTIVITY }

    @ParameterizedTest
    @EnumSource(Rule.class)
    void currentQualificationRulesApplyBeforeAnyOrderIsWritten(Rule rule) throws Exception {
        switch (rule) {
            case PUBLIC -> { }
            case ACTIVITY_MEMBER -> configure(ACTIVITY, buyer.getId());
            case ACTIVITY_NONMEMBER -> configure(ACTIVITY, otherPublisher.getId());
            case EMPTY_ACTIVITY -> configure(ACTIVITY, null);
            case SESSION_NONMEMBER -> {
                configure(ACTIVITY, buyer.getId()); configure(SESSION, otherPublisher.getId());
            }
            case SESSION_MEMBER -> {
                configure(ACTIVITY, otherPublisher.getId()); configure(SESSION, buyer.getId());
            }
            case EMPTY_SESSION -> { configure(ACTIVITY, buyer.getId()); configure(SESSION, null); }
            case EMPTY_SESSION_WITH_PUBLIC_ACTIVITY -> configure(SESSION, null);
        }
        if (rule == Rule.PUBLIC || rule == Rule.ACTIVITY_MEMBER || rule == Rule.SESSION_MEMBER) {
            createAs(buyer, request(auto(firstZone, 1, true)));
        } else {
            rejected(request(auto(firstZone, 1, true)), 403, "QUALIFICATION_DENIED");
        }
    }

    @Test
    void eachNewOrderSeesCommittedWhitelistChanges() throws Exception {
        configure(ACTIVITY, buyer.getId());
        JsonNode first = createAs(buyer, request(auto(firstZone, 1, true)));
        asPublisher(() -> { whitelist.removeMember(ACTIVITY, activity.getActivityId(), buyer.getId()); return null; });
        postJson("/ticket-orders", buyer, request(auto(firstZone, 1, true)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("QUALIFICATION_DENIED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order", Long.class)).isEqualTo(1);
        assertThat(orders.findById(first.path("orderId").asLong()).getStatus()).isEqualTo("PENDING_PAYMENT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "CANCELLED", "FINISHED"})
    void onlyPublishedActivitiesAcceptNewOrders(String state) throws Exception {
        jdbc.update("UPDATE activity SET status = ? WHERE activity_id = ?", state, activity.getActivityId());
        rejected(request(auto(firstZone, 1, true)), 409, "ACTIVITY_NOT_PUBLISHED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "FINISHED"})
    void nonScheduledSessionsRejectNewOrders(String state) throws Exception {
        jdbc.update("UPDATE activity_session SET status = ? WHERE activity_session_id = ?", state,
                session.getActivitySessionId());
        rejected(request(auto(firstZone, 1, true)), 409, "SESSION_NOT_AVAILABLE");
    }

    @Test
    void freeActivitiesCannotCreatePaidTicketOrders() throws Exception {
        jdbc.update("UPDATE activity SET type = 'FREE' WHERE activity_id = ?", activity.getActivityId());
        rejected(request(auto(firstZone, 1, true)), 400, "ACTIVITY_NOT_PAID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORDER_WINDOW_NOT_OPEN", "ORDER_WINDOW_CLOSED", "SESSION_STARTED"})
    void creationUsesTheDatabaseTimeAndCreationWindow(String expected) throws Exception {
        LocalDateTime now = time.currentTime();
        if ("ORDER_WINDOW_NOT_OPEN".equals(expected)) {
            jdbc.update("UPDATE activity SET open_time = ?, close_time = ? WHERE activity_id = ?",
                    now.plusMinutes(10), now.plusHours(1), activity.getActivityId());
        } else {
            jdbc.update("UPDATE activity SET open_time = ?, close_time = ? WHERE activity_id = ?",
                    now.minusHours(3), now.minusHours(2), activity.getActivityId());
            if ("SESSION_STARTED".equals(expected)) {
                jdbc.update("UPDATE activity_session SET start_time = ?, end_time = ? WHERE activity_session_id = ?",
                        now.minusHours(1), now.plusHours(1), session.getActivitySessionId());
            }
        }
        rejected(request(auto(firstZone, 1, true)), 409, expected);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingSessionOrActivityCannotProduceAnOrder(boolean missingActivity) throws Exception {
        var body = request(auto(firstZone, 1, true));
        if (!missingActivity) {
            body.put("sessionId", Long.MAX_VALUE);
            rejected(body, 404, "SESSION_NOT_FOUND");
            return;
        }
        jdbc.update("UPDATE activity_session SET activity_id = ? WHERE activity_session_id = ?",
                Long.MAX_VALUE, session.getActivitySessionId());
        try {
            rejected(body, 404, "ACTIVITY_NOT_FOUND");
        } finally {
            jdbc.update("UPDATE activity_session SET activity_id = ? WHERE activity_session_id = ?",
                    activity.getActivityId(), session.getActivitySessionId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingZoneFromAnotherSessionIsRejectedEvenForTheSamePublisher(boolean sameOwner) throws Exception {
        Activity other = insertDraft(sameOwner ? publisher : otherPublisher, "PAID");
        SessionZone foreign = insertZone(insertSession(other), "PAID", 2, 2);
        rejected(request(auto(foreign, 1, true)), 400, "INVALID_ZONE_FOR_SESSION");
    }

    @Test
    void missingZoneIsRejected() throws Exception {
        var item = auto(firstZone, 1, true);
        item.put("zoneId", Long.MAX_VALUE);
        rejected(request(item), 404, "ZONE_NOT_FOUND");
    }

    @Test
    void giftZoneCannotBePurchased() throws Exception {
        jdbc.update("UPDATE session_zone SET zone_type = 'GIFT', price = 0 WHERE zone_id = ?", secondZone.getZoneId());
        rejected(request(auto(secondZone, 1, true)), 400, "GIFT_ZONE_NOT_PURCHASABLE");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void everyItemMustMatchTheSessionSeatMode(boolean selectedSession) throws Exception {
        if (selectedSession) {
            selectSeatMode();
            rejected(request(auto(firstZone, 1, false)), 400, "INVALID_ALLOCATION_MODE");
        } else {
            long seatId = seatMapper.findByZoneId(firstZone.getZoneId()).get(0).getSeatId();
            rejected(request(selected(firstZone, seatId)), 400, "INVALID_ALLOCATION_MODE");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectedSeatMustExistAndBelongToItsZone(boolean missing) throws Exception {
        selectSeatMode();
        long seatId = missing ? Long.MAX_VALUE : seatMapper.findByZoneId(secondZone.getZoneId()).get(0).getSeatId();
        rejected(request(selected(firstZone, seatId)), missing ? 404 : 400,
                missing ? "SEAT_NOT_FOUND" : "INVALID_SEAT_FOR_ZONE");
    }

    @Test
    void theSameSeatCannotAppearInTwoItems() throws Exception {
        selectSeatMode();
        long seatId = seatMapper.findByZoneId(firstZone.getZoneId()).get(0).getSeatId();
        rejected(request(selected(firstZone, seatId), selected(firstZone, seatId)), 400, "DUPLICATE_REQUESTED_SEAT");
    }

    @Test
    void stockPrecheckAggregatesRepeatedItemsForTheSameZone() throws Exception {
        jdbc.update("UPDATE session_zone SET remains_amount = 2 WHERE zone_id = ?", firstZone.getZoneId());
        rejected(request(auto(firstZone, 2, true), auto(firstZone, 1, false)), 409, "INSUFFICIENT_CURRENT_INVENTORY");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void purchasePrecheckCombinesAllItemsWithExistingValidCount(boolean existingCount) throws Exception {
        if (existingCount) {
            jdbc.update("INSERT INTO user_session_purchase (user_id, session_id, valid_count) VALUES (?, ?, 3)",
                    buyer.getId(), session.getActivitySessionId());
        }
        rejected(request(auto(firstZone, existingCount ? 2 : 4, true), auto(secondZone, existingCount ? 2 : 3, false)),
                409, "PURCHASE_LIMIT_PRECHECK_FAILED");
    }

    @Test
    void largeQuantitiesCannotWrapTheStockAggregation() throws Exception {
        rejected(request(auto(firstZone, Integer.MAX_VALUE, true), auto(firstZone, Integer.MAX_VALUE, true)),
                409, "INSUFFICIENT_CURRENT_INVENTORY");
    }

    @Test
    void pendingOrdersNeitherReserveStockNorConsumePurchaseQuota() throws Exception {
        jdbc.update("UPDATE session_zone SET remains_amount = 2 WHERE zone_id = ?", firstZone.getZoneId());
        var before = inventorySnapshot();
        createAs(buyer, request(auto(firstZone, 2, true)));
        createAs(buyer, request(auto(firstZone, 2, false)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order WHERE status = 'PENDING_PAYMENT'", Long.class))
                .isEqualTo(2);
        assertThat(inventorySnapshot()).isEqualTo(before);
        jdbc.update("UPDATE session_zone SET remains_amount = 0 WHERE zone_id = ?", firstZone.getZoneId());
        postJson("/ticket-orders", buyer, request(auto(firstZone, 1, true)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_CURRENT_INVENTORY"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order", Long.class)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTO_ASSIGN", "SELECT_SEAT"})
    void successfulCreationRecordsOneHundredAvailableSeatsAndZeroUsedQuotaWithoutReservingEither(
            String allocationMode) throws Exception {
        LocalDateTime now = time.currentTime();
        Activity evidenceActivity = insertDraft(publisher, "PAID");
        evidenceActivity.setOpenTime(now.minusHours(1));
        evidenceActivity.setCloseTime(now.plusHours(1));
        jdbc.update("UPDATE activity SET open_time = ?, close_time = ? WHERE activity_id = ?",
                evidenceActivity.getOpenTime(), evidenceActivity.getCloseTime(), evidenceActivity.getActivityId());
        ActivitySession evidenceSession = insertSession(evidenceActivity);
        jdbc.update("""
                UPDATE activity_session SET capacity = 100, max_tickets_per_user = 6, seat_mode = ?
                WHERE activity_session_id = ?
                """, allocationMode, evidenceSession.getActivitySessionId());
        SessionZone evidenceZone = insertZone(evidenceSession, "PAID", 100, 100);
        publish(evidenceActivity, publisher).andExpect(status().isNoContent());
        jdbc.update("INSERT INTO user_session_purchase (user_id, session_id, valid_count) VALUES (?, ?, 0)",
                buyer.getId(), evidenceSession.getActivitySessionId());

        var body = request(auto(evidenceZone, 2, true), auto(evidenceZone, 1, false));
        if (allocationMode.equals("SELECT_SEAT")) {
            var configured = seatMapper.findByZoneId(evidenceZone.getZoneId());
            body.put("items", List.of(selected(evidenceZone, configured.get(0).getSeatId()),
                    selected(evidenceZone, configured.get(1).getSeatId())));
        }
        body.put("sessionId", evidenceSession.getActivitySessionId());
        NoReservationObservation before = noReservationObservation(evidenceZone, evidenceSession);
        var resourcesBefore = inventorySnapshot();
        assertThat(before).isEqualTo(new NoReservationObservation(100, 100, 0, 1, 0, 0, 0, 0));

        JsonNode response = createAs(buyer, body);
        assertThat(response.path("items")).hasSize(2);
        assertThat(response.path("status").asText()).isEqualTo("PENDING_PAYMENT");
        NoReservationObservation after = noReservationObservation(evidenceZone, evidenceSession);
        assertThat(after).isEqualTo(new NoReservationObservation(100, 100, 0, 1, 0, 0, 1, 2));
        assertThat(inventorySnapshot()).isEqualTo(resourcesBefore);

        Path evidenceFile = Path.of("target", "verification", "ticket-order-no-reservation-" + allocationMode + ".json");
        Files.createDirectories(evidenceFile.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(evidenceFile.toFile(), Map.of(
                "test", "successfulCreationRecordsOneHundredAvailableSeatsAndZeroUsedQuotaWithoutReservingEither",
                "observedAtDatabaseTime", time.currentTime(), "allocationMode", allocationMode,
                "orderId", response.path("orderId").asLong(), "before", before, "after", after));
    }

    private NoReservationObservation noReservationObservation(SessionZone zone, ActivitySession targetSession) {
        return new NoReservationObservation(
                jdbc.queryForObject("SELECT remains_amount FROM session_zone WHERE zone_id = ?", Integer.class, zone.getZoneId()),
                jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE zone_id = ? AND allocation_status = 'AVAILABLE'",
                        Long.class, zone.getZoneId()),
                jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE zone_id = ? AND allocation_status <> 'AVAILABLE'",
                        Long.class, zone.getZoneId()),
                jdbc.queryForObject("SELECT COUNT(*) FROM user_session_purchase WHERE user_id = ? AND session_id = ?",
                        Long.class, buyer.getId(), targetSession.getActivitySessionId()),
                jdbc.queryForObject("SELECT valid_count FROM user_session_purchase WHERE user_id = ? AND session_id = ?",
                        Integer.class, buyer.getId(), targetSession.getActivitySessionId()),
                jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Long.class),
                jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order", Long.class),
                jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order_item", Long.class));
    }

    record NoReservationObservation(int zoneRemainsAmount, long availableSeatCount, long unavailableSeatCount,
                                    long purchaseCounterRows, int validPurchaseCount, long ticketCount,
                                    long orderCount, long orderItemCount) { }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void protectedFieldsCannotReachTheRealCreationService(boolean itemField) throws Exception {
        var item = auto(firstZone, 1, true);
        var body = request(item);
        if (itemField) { item.put("unitPrice", new BigDecimal("0.01")); }
        else { body.put("buyerId", otherPublisher.getId()); }
        rejected(body, 400, "INVALID_TICKET_ORDER_REQUEST");
    }

    @Test
    void anonymousRequestsCannotCreateOrders() throws Exception {
        mvc.perform(post("/ticket-orders").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(auto(firstZone, 1, true)))))
                .andExpect(status().isUnauthorized());
        assertNoOrders();
    }

    @Test
    void disabledBuyerCannotReuseAnOldToken() throws Exception {
        String token = bearer(buyer);
        jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", buyer.getId());
        mvc.perform(post("/ticket-orders").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(auto(firstZone, 1, true)))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertNoOrders();
    }

    @ParameterizedTest(name = "rollback after {0}")
    @EnumSource(FailurePoint.class)
    void failuresInsideTheRealCreationTransactionLeaveNoOrdersOrResourceChanges(FailurePoint point) throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        // Keep an existing counter row, so the assertions also detect replacement or modification.
        jdbc.update("INSERT INTO user_session_purchase (user_id, session_id, valid_count) VALUES (?, ?, 0)",
                publisher.getId(), session.getActivitySessionId());
        var before = inventorySnapshot();
        CreateTicketOrderRequest input = objectMapper.convertValue(
                request(auto(firstZone, 1, true), auto(secondZone, 2, false)),
                CreateTicketOrderRequest.class);
        failure.armed.set(point);
        assertThatThrownBy(() -> asPublisher(() -> service.createOrder(input)))
                .hasRootCauseMessage("injected-order-write-" + point.name());

        WriteObservation observed = failure.observation.get();
        assertThat(observed).isNotNull();
        assertThat(failure.armed.get()).isNull();
        assertThat(observed.transactionActive()).isTrue();
        assertThat(observed.autoCommit()).isFalse();
        assertThat(observed.orderIds()).hasSize(1).allSatisfy(id -> assertThat(id).isPositive());
        int writtenItems = switch (point) {
            case AFTER_HEADER -> 0;
            case AFTER_FIRST_ITEM -> 1;
            case AFTER_ALL_ITEMS -> 2;
        };
        assertThat(observed.itemIds()).hasSize(writtenItems).allSatisfy(id -> assertThat(id).isPositive());
        assertThat(observed.itemZoneIds()).containsExactlyElementsOf(
                List.of(firstZone.getZoneId(), secondZone.getZoneId()).subList(0, writtenItems));
        assertThat(observed.resources()).isEqualTo(before); // Also unchanged before rollback occurs.

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertNoOrders();
        assertThat(orders.findByBuyerIdAndRequestId(publisher.getId(), input.getRequestId())).isNull();
        assertThat(orders.findById(observed.orderIds().get(0))).isNull();
        assertThat(items.findByOrderId(observed.orderIds().get(0))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?",
                Long.class, observed.orderIds().get(0))).isZero();
        assertThat(inventorySnapshot()).isEqualTo(before);
    }

    @Test
    void duplicateRequestIdWithTheSamePayloadReturnsTheOriginalOrder() throws Exception {
        var body = request(auto(firstZone, 1, true));
        JsonNode original = createAs(buyer, body);
        var replay = postJson("/ticket-orders", buyer, body).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(objectMapper.readTree(replay.getContentAsString())).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order_item", Long.class)).isEqualTo(1);
    }

    @Test
    void reorderedItemsReplayTheOriginalResponseWithoutReorderingOrRepricingStoredItems() throws Exception {
        var first = auto(firstZone, 2, true);
        var second = auto(secondZone, 1, false);
        var body = request(first, second);
        JsonNode original = createAs(buyer, body);
        var before = inventorySnapshot();
        body.put("items", List.of(second, first));
        assertThat(replayAs(buyer, body)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order_item", Long.class)).isEqualTo(2);
        assertThat(inventorySnapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sessionId", "zoneId", "allocationMode", "requestedSeatId", "quantity", "acceptNonAdjacent"})
    void changingAnyPurchaseIntentFieldConflictsBeforeNewOrderValidation(String field) throws Exception {
        Map<String, Object> item;
        if (field.equals("requestedSeatId")) {
            selectSeatMode();
            item = selected(firstZone, seatMapper.findByZoneId(firstZone.getZoneId()).get(0).getSeatId());
        } else { item = auto(firstZone, 2, true); }
        var body = request(item);
        JsonNode original = createAs(buyer, body);
        switch (field) {
            case "sessionId" -> body.put(field, Long.MAX_VALUE);
            case "zoneId" -> item.put(field, secondZone.getZoneId());
            case "allocationMode" -> {
                item.clear();
                item.putAll(selected(firstZone, seatMapper.findByZoneId(firstZone.getZoneId()).get(0).getSeatId()));
            }
            case "requestedSeatId" -> item.put(field, seatMapper.findByZoneId(firstZone.getZoneId()).get(1).getSeatId());
            case "quantity" -> item.put(field, 3);
            case "acceptNonAdjacent" -> item.put(field, false);
            default -> throw new AssertionError(field);
        }
        assertIdempotencyConflict(body);
        assertThat(orders.findById(original.path("orderId").asLong()).getRequestId())
                .isEqualTo(body.get("requestId"));
    }

    @Test
    void equalMoneyDoesNotMakeTwoDifferentPurchasesTheSameRequest() throws Exception {
        var body = request(auto(firstZone, 2, true)); // 2 * 0.10
        JsonNode original = createAs(buyer, body);
        body.put("items", List.of(auto(secondZone, 1, true))); // 1 * 0.20
        assertIdempotencyConflict(body);
        assertThat(orders.findById(original.path("orderId").asLong()).getTotalAmount()).isEqualByComparingTo("0.20");
        assertThat(items.findByOrderId(original.path("orderId").asLong()).get(0).getZoneId())
                .isEqualTo(firstZone.getZoneId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void normalizationPreservesRepeatedRowsAndDoesNotMergePurchaseLines(boolean merge) throws Exception {
        var body = request(auto(firstZone, 1, false), auto(firstZone, 1, false));
        JsonNode original = createAs(buyer, body);
        assertThat(replayAs(buyer, body)).isEqualTo(original);
        body.put("items", List.of(auto(firstZone, merge ? 2 : 1, false)));
        assertIdempotencyConflict(body);
    }

    @Test
    void eachBuyerOwnsAnIndependentRequestIdScope() throws Exception {
        var first = request(auto(firstZone, 1, true));
        var second = new HashMap<>(first);
        second.put("items", List.of(auto(secondZone, 2, false)));
        JsonNode one = createAs(buyer, first);
        JsonNode two = createAs(otherPublisher, second);
        assertThat(one.path("orderId")).isNotEqualTo(two.path("orderId"));
        assertThat(replayAs(buyer, first)).isEqualTo(one);
        assertThat(replayAs(otherPublisher, second)).isEqualTo(two);
        assertThat(orders.findById(one.path("orderId").asLong()).getBuyerId()).isEqualTo(buyer.getId());
        assertThat(orders.findById(two.path("orderId").asLong()).getBuyerId()).isEqualTo(otherPublisher.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WINDOW_CLOSED", "WHITELIST_CHANGED", "STOCK_AND_QUOTA_CHANGED", "PRICE_CHANGED", "SESSION_CANCELLED"})
    void replayIsNotANewPurchaseAndKeepsTheOriginalSnapshot(String change) throws Exception {
        var body = request(auto(firstZone, 1, true));
        JsonNode original = createAs(buyer, body);
        switch (change) {
            case "WINDOW_CLOSED" -> jdbc.update("UPDATE activity SET open_time = ?, close_time = ? WHERE activity_id = ?",
                    time.currentTime().minusHours(2), time.currentTime().minusHours(1), activity.getActivityId());
            case "WHITELIST_CHANGED" -> configure(SESSION, null);
            case "STOCK_AND_QUOTA_CHANGED" -> {
                jdbc.update("UPDATE session_zone SET remains_amount = 0 WHERE zone_id = ?", firstZone.getZoneId());
                jdbc.update("INSERT INTO user_session_purchase (user_id, session_id, valid_count) VALUES (?, ?, 6)",
                        buyer.getId(), session.getActivitySessionId());
            }
            case "PRICE_CHANGED" -> jdbc.update("UPDATE session_zone SET price = 99.99 WHERE zone_id = ?", firstZone.getZoneId());
            case "SESSION_CANCELLED" -> jdbc.update("UPDATE activity_session SET status = 'CANCELLED' WHERE activity_session_id = ?",
                    session.getActivitySessionId());
            default -> throw new AssertionError(change);
        }
        var before = inventorySnapshot();
        assertThat(replayAs(buyer, body)).isEqualTo(original);
        assertThat(inventorySnapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order", Long.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "CLOSED"})
    void replayReturnsThePersistedStateWithoutResettingOrExtendingTheOrder(String state) throws Exception {
        var body = request(auto(firstZone, 1, true));
        JsonNode original = createAs(buyer, body);
        long id = original.path("orderId").asLong();
        if (state.equals("PAID")) {
            jdbc.update("UPDATE ticket_order SET status = 'PAID', paid_at = created_at WHERE order_id = ?", id);
        } else {
            jdbc.update("UPDATE ticket_order SET status = 'CLOSED', closed_reason = 'TEST_CLOSED' WHERE order_id = ?", id);
        }
        JsonNode replayed = replayAs(buyer, body);
        assertThat(replayed.path("orderId").asLong()).isEqualTo(id);
        assertThat(replayed.path("status").asText()).isEqualTo(state);
        assertThat(replayed.path("createdAt")).isEqualTo(original.path("createdAt"));
        assertThat(replayed.path("expiresAt")).isEqualTo(original.path("expiresAt"));
        assertThat(replayed.path("items")).isEqualTo(original.path("items"));
    }

    @Test
    void anExpiredPendingOrderIsReplayedWithoutRenewingItsDatabaseExpiry() throws Exception {
        var body = request(auto(firstZone, 1, true));
        long id = createAs(buyer, body).path("orderId").asLong();
        jdbc.update("UPDATE ticket_order SET created_at = '2000-01-01 00:00:00.000' WHERE order_id = ?", id);
        JsonNode replayed = replayAs(buyer, body);
        assertThat(replayed.path("orderId").asLong()).isEqualTo(id);
        assertThat(orders.findById(id).getExpiresAt()).isEqualTo(LocalDateTime.of(2000, 1, 1, 0, 15));
    }

    @Test
    void aDisabledBuyerCannotReplayAnExistingOrderWithAnOldToken() throws Exception {
        var body = request(auto(firstZone, 1, true));
        createAs(buyer, body);
        String token = bearer(buyer);
        jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", buyer.getId());
        mvc.perform(post("/ticket-orders").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order", Long.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eitherConsumerRoleCanReadItsOwnOrderWithAllPersistedDetails(boolean publisherRole) throws Exception {
        var actor = publisherRole ? publisher : buyer;
        JsonNode created = createAs(actor, request(auto(firstZone, 2, true), auto(secondZone, 1, false)));
        var before = inventorySnapshot();
        var response = mvc.perform(get("/ticket-orders/{id}", created.path("orderId").asLong())
                        .header(HttpHeaders.AUTHORIZATION, bearer(actor)))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(objectMapper.readTree(response.getContentAsString())).isEqualTo(created);
        assertThat(inventorySnapshot()).isEqualTo(before);
    }

    @Test
    void ownOrderDetailsIncludeTheOriginallyRequestedSeat() throws Exception {
        selectSeatMode();
        long seatId = seatMapper.findByZoneId(firstZone.getZoneId()).get(0).getSeatId();
        JsonNode created = createAs(buyer, request(selected(firstZone, seatId)));
        var response = mvc.perform(get("/ticket-orders/{id}", created.path("orderId").asLong())
                        .header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].requestedSeatId").value(seatId))
                .andReturn().getResponse();
        assertThat(objectMapper.readTree(response.getContentAsString())).isEqualTo(created);
    }

    @ParameterizedTest
    @CsvSource({"USER,false", "USER,true", "PUBLISHER,false", "PUBLISHER,true",
            "ACTIVITY_OWNER,false", "ACTIVITY_OWNER,true"})
    void otherUsersAndPublishersCannotReadTheBuyersOrderEvenWithAForgedBuyerId(
            String actorType, boolean forgedBuyerId) throws Exception {
        JsonNode created = createAs(buyer, request(auto(firstZone, 1, true)));
        var actor = actorType.equals("ACTIVITY_OWNER") ? publisher : otherPublisher;
        if (actorType.equals("USER")) {
            actor.setRole("USER");
            jdbc.update("UPDATE app_user SET role = 'USER' WHERE id = ?", actor.getId());
        }
        var before = inventorySnapshot();
        var call = get("/ticket-orders/{id}", created.path("orderId").asLong())
                .header(HttpHeaders.AUTHORIZATION, bearer(actor));
        if (forgedBuyerId) { call.queryParam("buyerId", buyer.getId().toString()); }
        mvc.perform(call).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORDER_ACCESS_DENIED"))
                .andExpect(jsonPath("$.orderId").doesNotExist())
                .andExpect(jsonPath("$.items").doesNotExist())
                .andExpect(jsonPath("$.totalAmount").doesNotExist());
        assertThat(inventorySnapshot()).isEqualTo(before);
        assertThat(orders.findById(created.path("orderId").asLong()).getBuyerId()).isEqualTo(buyer.getId());
    }

    @Test
    void aBuyerIdQueryParameterCannotReplaceTheAuthenticatedOwner() throws Exception {
        JsonNode created = createAs(buyer, request(auto(firstZone, 1, true)));
        var response = mvc.perform(get("/ticket-orders/{id}", created.path("orderId").asLong())
                        .header(HttpHeaders.AUTHORIZATION, bearer(buyer))
                        .queryParam("buyerId", otherPublisher.getId().toString()))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(objectMapper.readTree(response.getContentAsString())).isEqualTo(created);
    }

    @Test
    void anonymousUsersCannotReadOrderDetailsBySupplyingABuyerId() throws Exception {
        JsonNode created = createAs(buyer, request(auto(firstZone, 1, true)));
        mvc.perform(get("/ticket-orders/{id}", created.path("orderId").asLong())
                        .queryParam("buyerId", buyer.getId().toString()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void aDisabledOwnerCannotReadItsOrderWithAPreviouslyIssuedToken() throws Exception {
        JsonNode created = createAs(buyer, request(auto(firstZone, 1, true)));
        String token = bearer(buyer);
        jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", buyer.getId());
        mvc.perform(get("/ticket-orders/{id}", created.path("orderId").asLong())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void missingOrdersReturnAnExplicitNotFoundError() throws Exception {
        mvc.perform(get("/ticket-orders/{id}", Long.MAX_VALUE)
                        .header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "not-a-number", "9223372036854775808"})
    void invalidOrderIdsReturnTheOrderErrorContract(String orderId) throws Exception {
        mvc.perform(get("/ticket-orders/{id}", orderId).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TICKET_ORDER_REQUEST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_PAYMENT", "PAID", "CLOSED"})
    void orderDetailsReturnThePersistedStateWithoutRenewingAnOrder(String state) throws Exception {
        JsonNode created = createAs(buyer, request(auto(firstZone, 1, true)));
        long orderId = created.path("orderId").asLong();
        if (state.equals("PAID")) {
            jdbc.update("UPDATE ticket_order SET status = 'PAID', paid_at = created_at WHERE order_id = ?", orderId);
        } else if (state.equals("CLOSED")) {
            jdbc.update("UPDATE ticket_order SET status = 'CLOSED', closed_reason = 'TEST_CLOSED' WHERE order_id = ?", orderId);
        } else {
            jdbc.update("UPDATE ticket_order SET created_at = '2000-01-01 00:00:00.000' WHERE order_id = ?", orderId);
        }
        var headersBefore = jdbc.queryForList("SELECT * FROM ticket_order");
        var itemsBefore = jdbc.queryForList("SELECT * FROM ticket_order_item ORDER BY order_item_id");
        var response = mvc.perform(get("/ticket-orders/{id}", orderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(state))
                .andReturn().getResponse();
        JsonNode actual = objectMapper.readTree(response.getContentAsString());
        assertThat(actual.path("items")).isEqualTo(created.path("items"));
        assertThat(actual.path("totalAmount")).isEqualTo(created.path("totalAmount"));
        assertThat(actual.path("paidAt").isNull()).isEqualTo(!state.equals("PAID"));
        assertThat(jdbc.queryForList("SELECT * FROM ticket_order")).isEqualTo(headersBefore);
        assertThat(jdbc.queryForList("SELECT * FROM ticket_order_item ORDER BY order_item_id")).isEqualTo(itemsBefore);
    }

    @Test
    void existingOrderDetailsRemainVisibleAfterTheWindowClosesAndEligibilityIsRemoved() throws Exception {
        JsonNode created = createAs(buyer, request(auto(firstZone, 1, true)));
        configure(SESSION, null);
        jdbc.update("UPDATE activity SET open_time = ?, close_time = ? WHERE activity_id = ?",
                time.currentTime().minusHours(2), time.currentTime().minusHours(1), activity.getActivityId());
        var response = mvc.perform(get("/ticket-orders/{id}", created.path("orderId").asLong())
                        .header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(objectMapper.readTree(response.getContentAsString())).isEqualTo(created);
    }

    @Test
    void directServiceCallsAlsoEnforceOrderOwnership() throws Exception {
        JsonNode created = createAs(buyer, request(auto(firstZone, 1, true)));
        assertThatThrownBy(() -> asPublisher(() -> queries.getOwnOrder(created.path("orderId").asLong())))
                .isInstanceOfSatisfying(com.limitactivitysystem.ticketorder.exception.TicketOrderException.class,
                        exception -> assertThat(exception.getErrorCode().name()).isEqualTo("ORDER_ACCESS_DENIED"));
    }

    private JsonNode replayAs(com.limitactivitysystem.identity.model.AppUser actor, Map<String, Object> body)
            throws Exception {
        String response = postJson("/ticket-orders", actor, body).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private void assertIdempotencyConflict(Map<String, Object> body) throws Exception {
        var before = inventorySnapshot();
        postJson("/ticket-orders", buyer, body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order", Long.class)).isEqualTo(1);
        assertThat(inventorySnapshot()).isEqualTo(before);
    }

    private void configure(WhitelistScopeType scope, Long memberId) throws Exception {
        long scopeId = scope == ACTIVITY ? activity.getActivityId() : session.getActivitySessionId();
        asPublisher(() -> whitelist.enable(scope, scopeId));
        if (memberId != null) { asPublisher(() -> whitelist.addMember(scope, scopeId, memberId)); }
    }

    private void selectSeatMode() {
        jdbc.update("UPDATE activity_session SET seat_mode = 'SELECT_SEAT' WHERE activity_session_id = ?",
                session.getActivitySessionId());
    }

    private JsonNode createAs(com.limitactivitysystem.identity.model.AppUser actor, Map<String, Object> body)
            throws Exception {
        String response = postJson("/ticket-orders", actor, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private void rejected(Map<String, Object> body, int httpStatus, String code) throws Exception {
        var before = inventorySnapshot();
        postJson("/ticket-orders", buyer, body).andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(code));
        assertNoOrders();
        assertThat(inventorySnapshot()).isEqualTo(before);
    }

    private void assertNoOrders() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order_item", Long.class)).isZero();
    }

    @SafeVarargs
    private final Map<String, Object> request(Map<String, Object>... items) {
        return new HashMap<>(Map.of("requestId", UUID.randomUUID().toString(),
                "sessionId", session.getActivitySessionId(), "items", List.of(items)));
    }

    private Map<String, Object> auto(SessionZone zone, int quantity, boolean accept) {
        return new HashMap<>(Map.of("zoneId", zone.getZoneId(), "allocationMode", "AUTO_ASSIGN",
                "quantity", quantity, "acceptNonAdjacent", accept));
    }

    private Map<String, Object> selected(SessionZone zone, long seatId) {
        return new HashMap<>(Map.of("zoneId", zone.getZoneId(), "allocationMode", "SELECT_SEAT",
                "quantity", 1, "acceptNonAdjacent", false, "requestedSeatId", seatId));
    }

    private Map<String, List<Map<String, Object>>> inventorySnapshot() {
        return inventorySnapshot(jdbc);
    }

    private static Map<String, List<Map<String, Object>>> inventorySnapshot(JdbcTemplate jdbc) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("session_zone", "session_seat", "user_session_purchase", "ticket")) {
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1"));
        }
        return result;
    }

    enum FailurePoint { AFTER_HEADER, AFTER_FIRST_ITEM, AFTER_ALL_ITEMS }

    record WriteObservation(List<Long> orderIds, List<Long> itemIds, List<Long> itemZoneIds,
                            Map<String, List<Map<String, Object>>> resources,
                            boolean transactionActive, boolean autoCommit) { }

    // Imported by this test class only. No production switch or HTTP parameter enables these failures.
    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    static class OrderWriteFailure implements Interceptor {
        final AtomicReference<FailurePoint> armed = new AtomicReference<>();
        final AtomicReference<WriteObservation> observation = new AtomicReference<>();
        private final JdbcTemplate jdbc;

        OrderWriteFailure(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        void reset() {
            armed.set(null);
            observation.set(null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            boolean batch = statement.getId().equals(TicketOrderItemMapper.class.getName() + ".batchInsert");
            Executor executor = (Executor) invocation.getTarget();
            if (batch && armed.compareAndSet(FailurePoint.AFTER_FIRST_ITEM, null)) {
                Map<String, Object> parameters = (Map<String, Object>) invocation.getArgs()[1];
                List<TicketOrderItem> prepared = (List<TicketOrderItem>) parameters.get("items");
                assertThat(prepared).hasSize(2);
                // Execute the real single-row INSERT on the same MyBatis Executor and Spring transaction.
                MappedStatement insert = statement.getConfiguration().getMappedStatement(
                        TicketOrderItemMapper.class.getName() + ".insert");
                assertThat(executor.update(insert, prepared.get(0))).isEqualTo(1);
                fail(executor, FailurePoint.AFTER_FIRST_ITEM);
            }
            Object result = invocation.proceed();
            boolean header = statement.getId().equals(TicketOrderMapper.class.getName() + ".insertDuringCreationWindow");
            if (header && armed.compareAndSet(FailurePoint.AFTER_HEADER, null)) {
                fail(executor, FailurePoint.AFTER_HEADER);
            }
            if (batch && armed.compareAndSet(FailurePoint.AFTER_ALL_ITEMS, null)) {
                fail(executor, FailurePoint.AFTER_ALL_ITEMS);
            }
            return result;
        }

        private void fail(Executor executor, FailurePoint point) throws Exception {
            observation.set(new WriteObservation(
                    jdbc.queryForList("SELECT order_id FROM ticket_order ORDER BY order_id", Long.class),
                    jdbc.queryForList("SELECT order_item_id FROM ticket_order_item ORDER BY order_item_id", Long.class),
                    jdbc.queryForList("SELECT zone_id FROM ticket_order_item ORDER BY order_item_id", Long.class),
                    inventorySnapshot(jdbc), TransactionSynchronizationManager.isActualTransactionActive(),
                    executor.getTransaction().getConnection().getAutoCommit()));
            throw new IllegalStateException("injected-order-write-" + point.name());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean IsolatedQualificationMySqlDataSource dataSource(DataSourceProperties properties) throws Exception {
            return new IsolatedQualificationMySqlDataSource(properties);
        }
        @Bean OrderWriteFailure orderWriteFailure(JdbcTemplate jdbc) { return new OrderWriteFailure(jdbc); }
    }
}
