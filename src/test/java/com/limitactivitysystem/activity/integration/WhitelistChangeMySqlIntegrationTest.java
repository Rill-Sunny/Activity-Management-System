package com.limitactivitysystem.activity.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.limitactivitysystem.activity.model.*;
import com.limitactivitysystem.refund.dto.TicketRefundResponse;
import com.limitactivitysystem.reservation.dto.ReservationResponse;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.ticketorder.mapper.TicketMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Day 12 A-D through real publisher and consumer APIs, without an outer test transaction. */
@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import(CurrentSchemaTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class WhitelistChangeMySqlIntegrationTest extends PaymentResourceTestSupport {
    enum Scope { ACTIVITY, SESSION }
    enum Change { MEMBER_REMOVED, CONFIG_REMOVED }
    @Autowired private TicketMapper tickets;
    private Activity paidActivity, freeActivity;
    private ActivitySession paidSession, freeSession;
    private SessionZone paidZone;
    private String buyerJwt;

    @BeforeEach void publishPaidAndFreeScopesAndKeepOneBuyerJwt() throws Exception {
        var now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class);
        paidActivity = insertDraft(publisher, "PAID"); freeActivity = insertDraft(publisher, "FREE");
        for (var activity : List.of(paidActivity, freeActivity)) {
            jdbc.update("UPDATE activity SET open_time = ?, close_time = ? WHERE activity_id = ?", now.minusDays(10), now.plusDays(1), activity.getActivityId());
        }
        paidSession = insertSession(paidActivity); freeSession = insertSession(freeActivity);
        for (var session : List.of(paidSession, freeSession)) {
            jdbc.update("UPDATE activity_session SET start_time = ?, end_time = ? WHERE activity_session_id = ?",
                    now.plusDays(20), now.plusDays(20).plusHours(2), session.getActivitySessionId());
        }
        jdbc.update("UPDATE activity_session SET capacity = 4, max_tickets_per_user = 4 WHERE activity_session_id = ?", paidSession.getActivitySessionId());
        jdbc.update("UPDATE activity_session SET capacity = 2 WHERE activity_session_id = ?", freeSession.getActivitySessionId());
        paidZone = insertZone(paidSession, "PAID", 4, 4);
        publish(paidActivity, publisher).andExpect(status().isNoContent()); publish(freeActivity, publisher).andExpect(status().isNoContent());
        buyerJwt = bearer(buyer);
    }

    @AfterEach void reconcileThenRemoveOnlyTheIsolatedHistory() {
        try { consistent(); }
        finally {
            jdbc.update("DELETE FROM whitelist_member"); jdbc.update("DELETE FROM whitelist_config");
            jdbc.update("DELETE FROM ticket_refund_item"); jdbc.update("DELETE FROM ticket_refund_request");
            jdbc.update("DELETE FROM free_reservation"); jdbc.update("DELETE FROM free_session_quota");
        }
    }

    @ParameterizedTest @EnumSource(Scope.class)
    void aPendingPaymentRechecksMembershipUsingTheSameJwt(Scope scope) throws Exception {
        allow(scope, paidActivity, paidSession);
        Long orderId = createOrder(orderBody());
        assertThat(orders.findById(orderId).getStatus()).isEqualTo("PENDING_PAYMENT");
        var beforeRemoval = rightsSnapshot(); remove(scope, paidActivity, paidSession, Change.MEMBER_REMOVED);
        assertThat(rightsSnapshot()).isEqualTo(beforeRemoval);
        var beforePayment = rightsSnapshot();
        mvc.perform(post("/ticket-orders/{id}/pay", orderId).header(HttpHeaders.AUTHORIZATION, buyerJwt))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("QUALIFICATION_DENIED"));
        assertThat(rightsSnapshot()).isEqualTo(beforePayment);
        assertThat(orders.findById(orderId).getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(tickets.findByOrderId(orderId)).isEmpty();
        assertThat(seatMapper.findByZoneId(paidZone.getZoneId())).allSatisfy(seat -> assertThat(seat.getAllocationStatus()).isEqualTo("AVAILABLE"));
        assertThat(zoneMapper.findById(paidZone.getZoneId()).getRemainsAmount()).isEqualTo(4);
        assertThat(purchases.findValidCount(buyer.getId(), paidSession.getActivitySessionId())).isZero(); consistent();
        evidence("day12-whitelist-A-pending-" + scope, Map.of("scope", scope, "sameBuyerJwt", true, "orderId", orderId,
                "paymentHttp", 403, "paymentCode", "QUALIFICATION_DENIED", "orderStatus", "PENDING_PAYMENT",
                "resourceSnapshotUnchanged", true, "tickets", 0, "zoneRemaining", 4));
    }

    static Stream<Arguments> existingRightsChanges() {
        return Arrays.stream(Scope.values()).flatMap(scope -> Arrays.stream(Change.values()).map(change -> Arguments.of(scope, change)));
    }

    @ParameterizedTest @MethodSource("existingRightsChanges")
    void bPaidTicketsAndPaymentReplaySurviveWhitelistChanges(Scope scope, Change change) throws Exception {
        allow(scope, paidActivity, paidSession);
        Long orderId = createOrder(orderBody()); var originalPayment = pay(orderId);
        var before = rightsSnapshot(); remove(scope, paidActivity, paidSession, change);
        assertThat(rightsSnapshot()).isEqualTo(before);
        assertThat(orders.findById(orderId).getStatus()).isEqualTo("PAID");
        var ticket = tickets.findByOrderId(orderId).get(0);
        assertThat(ticket.getStatus()).isEqualTo("VALID"); assertThat(seatMapper.findById(ticket.getSeatId()).getAllocationStatus()).isEqualTo("SOLD");
        assertThat(pay(orderId)).isEqualTo(originalPayment);
        assertThat(rightsSnapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_refund_request", Integer.class)).isZero();
        assertThat(purchases.findValidCount(buyer.getId(), paidSession.getActivitySessionId())).isEqualTo(1); consistent();
        evidence("day12-whitelist-B-paid-" + scope + "-" + change, Map.of("scope", scope, "change", change,
                "sameBuyerJwt", true, "orderStatus", "PAID", "ticket", ticket, "seatStatus", "SOLD",
                "resourceSnapshotUnchanged", true, "paymentReplayIsOriginal", true, "automaticRefunds", 0));
    }

    @ParameterizedTest @MethodSource("existingRightsChanges")
    void cSuccessfulReservationSurvivesWhitelistChanges(Scope scope, Change change) throws Exception {
        allow(scope, freeActivity, freeSession);
        var original = reserve(); var before = rightsSnapshot(); remove(scope, freeActivity, freeSession, change);
        assertThat(rightsSnapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM free_reservation WHERE reservation_id = ?", String.class, original.reservationId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT cancelled_at FROM free_reservation WHERE reservation_id = ?", LocalDateTime.class, original.reservationId())).isNull();
        assertThat(freeRemaining()).isEqualTo(1); consistent();
        evidence("day12-whitelist-C-reservation-" + scope + "-" + change, Map.of("scope", scope, "change", change,
                "reservation", original, "finalStatus", "ACTIVE", "freeRemaining", 1, "resourceSnapshotUnchanged", true));
    }

    @ParameterizedTest @EnumSource(Scope.class)
    void dCancelThenRebookUsesCurrentMembershipRatherThanHistory(Scope scope) throws Exception {
        allow(scope, freeActivity, freeSession); var original = reserve();
        var beforeRemoval = rightsSnapshot(); remove(scope, freeActivity, freeSession, Change.MEMBER_REMOVED);
        assertThat(rightsSnapshot()).isEqualTo(beforeRemoval);
        var response = mvc.perform(post("/reservations/{id}/cancel", original.reservationId()).header(HttpHeaders.AUTHORIZATION, buyerJwt))
                .andExpect(status().isOk()).andReturn().getResponse();
        var cancelled = objectMapper.readValue(response.getContentAsString(), ReservationResponse.class);
        assertThat(cancelled.status()).isEqualTo("CANCELLED"); assertThat(cancelled.createdAt()).isEqualTo(original.createdAt());
        assertThat(cancelled.cancelledAt()).isNotNull(); assertThat(freeRemaining()).isEqualTo(2);
        var beforeRebooking = rightsSnapshot();
        mvc.perform(post("/sessions/{id}/reservations", freeSession.getActivitySessionId()).header(HttpHeaders.AUTHORIZATION, buyerJwt))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("QUALIFICATION_DENIED"));
        assertThat(rightsSnapshot()).isEqualTo(beforeRebooking);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM free_reservation WHERE status = 'ACTIVE'", Integer.class)).isZero(); consistent();
        evidence("day12-whitelist-D-rebooking-" + scope, Map.of("scope", scope, "sameBuyerJwt", true, "cancelled", cancelled,
                "rebookingHttp", 403, "rebookingCode", "QUALIFICATION_DENIED", "freeRemaining", 2,
                "historyPreserved", true, "resourceSnapshotUnchangedAfterRejection", true));
    }

    @ParameterizedTest @EnumSource(Scope.class)
    void dRefundThenNewOrderUsesCurrentMembershipWhileOldIntentOnlyReplaysHistory(Scope scope) throws Exception {
        allow(scope, paidActivity, paidSession); var originalIntent = orderBody();
        Long orderId = createOrder(originalIntent); pay(orderId);
        var sold = tickets.findByOrderId(orderId).get(0);
        var beforeRemoval = rightsSnapshot(); remove(scope, paidActivity, paidSession, Change.MEMBER_REMOVED);
        assertThat(rightsSnapshot()).isEqualTo(beforeRemoval);
        var application = mvc.perform(post("/ticket-refunds").header(HttpHeaders.AUTHORIZATION, buyerJwt).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("requestId", "refund-after-list-removal", "orderId", orderId,
                        "ticketIds", List.of(sold.getTicketId()), "reason", "USER_REQUEST"))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var accepted = objectMapper.readValue(application.getContentAsString(), TicketRefundResponse.class);
        assertThat(accepted.status()).isEqualTo("PENDING"); assertThat(tickets.findByOrderId(orderId).get(0).getStatus()).isEqualTo("VALID");
        var processed = mvc.perform(post("/ticket-refunds/{id}/process", accepted.refundId()).header(HttpHeaders.AUTHORIZATION, buyerJwt))
                .andExpect(status().isOk()).andReturn().getResponse();
        var completed = objectMapper.readValue(processed.getContentAsString(), TicketRefundResponse.class);
        assertThat(completed.status()).isEqualTo("SUCCEEDED"); assertThat(orders.findById(orderId).getStatus()).isEqualTo("REFUNDED");
        assertThat(tickets.findByOrderId(orderId).get(0).getStatus()).isEqualTo("REFUNDED");
        assertThat(seatMapper.findById(sold.getSeatId()).getAllocationStatus()).isEqualTo("AVAILABLE");
        assertThat(zoneMapper.findById(paidZone.getZoneId()).getRemainsAmount()).isEqualTo(4);
        assertThat(purchases.findValidCount(buyer.getId(), paidSession.getActivitySessionId())).isZero();
        var beforeNewIntent = rightsSnapshot();
        mvc.perform(post("/ticket-orders").header(HttpHeaders.AUTHORIZATION, buyerJwt).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(orderBody())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("QUALIFICATION_DENIED"));
        assertThat(rightsSnapshot()).isEqualTo(beforeNewIntent);
        // Replaying the original request is a history read, not permission to participate again.
        mvc.perform(post("/ticket-orders").header(HttpHeaders.AUTHORIZATION, buyerJwt).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(originalIntent))).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId)).andExpect(jsonPath("$.status").value("REFUNDED"));
        assertThat(rightsSnapshot()).isEqualTo(beforeNewIntent); consistent();
        evidence("day12-whitelist-D-repurchase-" + scope, Map.of("scope", scope, "sameBuyerJwt", true, "refund", completed,
                "orderStatus", "REFUNDED", "seatStatus", "AVAILABLE", "validCount", 0,
                "newOrderHttp", 403, "newOrderCode", "QUALIFICATION_DENIED", "oldRequestOnlyReadsHistory", true));
    }

    private void allow(Scope scope, Activity activity, ActivitySession session) throws Exception {
        // A SESSION list fully overrides a configured empty ACTIVITY list, and deletion restores inheritance.
        enable(whitelistRoot(Scope.ACTIVITY, activity, session));
        String target = whitelistRoot(scope, activity, session);
        if (scope == Scope.SESSION) { enable(target); }
        postJson(target + "/members", publisher, Map.of("userId", buyer.getId())).andExpect(status().isCreated());
    }
    private void enable(String path) throws Exception {
        mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, bearer(publisher))).andExpect(status().isOk());
    }
    private void remove(Scope scope, Activity activity, ActivitySession session, Change change) throws Exception {
        String root = whitelistRoot(scope, activity, session);
        String path = change == Change.MEMBER_REMOVED ? root + "/members/" + buyer.getId() : root;
        mvc.perform(delete(path).header(HttpHeaders.AUTHORIZATION, bearer(publisher))).andExpect(status().isNoContent());
    }
    private String whitelistRoot(Scope scope, Activity activity, ActivitySession session) {
        return "/publisher/" + (scope == Scope.ACTIVITY ? "activities/" + activity.getActivityId() : "sessions/" + session.getActivitySessionId()) + "/whitelist";
    }
    private Map<String, Object> orderBody() {
        Long seat = seatMapper.findByZoneId(paidZone.getZoneId()).get(0).getSeatId();
        return Map.of("requestId", UUID.randomUUID().toString(), "sessionId", paidSession.getActivitySessionId(),
                "items", List.of(Map.of("zoneId", paidZone.getZoneId(), "allocationMode", "SELECT_SEAT", "requestedSeatId", seat,
                        "quantity", 1, "acceptNonAdjacent", false)));
    }
    private Long createOrder(Map<String, Object> body) throws Exception {
        var response = mvc.perform(post("/ticket-orders").header(HttpHeaders.AUTHORIZATION, buyerJwt).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andExpect(status().isCreated()).andReturn().getResponse();
        return objectMapper.readTree(response.getContentAsString()).path("orderId").asLong();
    }
    private JsonNode pay(Long orderId) throws Exception {
        var response = mvc.perform(post("/ticket-orders/{id}/pay", orderId).header(HttpHeaders.AUTHORIZATION, buyerJwt))
                .andExpect(status().isOk()).andReturn().getResponse();
        return objectMapper.readTree(response.getContentAsString());
    }
    private ReservationResponse reserve() throws Exception {
        var response = mvc.perform(post("/sessions/{id}/reservations", freeSession.getActivitySessionId()).header(HttpHeaders.AUTHORIZATION, buyerJwt))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return objectMapper.readValue(response.getContentAsString(), ReservationResponse.class);
    }
    private int freeRemaining() { return jdbc.queryForObject("SELECT remaining FROM free_session_quota WHERE session_id = ?", Integer.class, freeSession.getActivitySessionId()); }
    private List<List<Map<String, Object>>> rightsSnapshot() {
        return List.of("ticket_order", "ticket_order_item", "ticket", "session_zone", "session_seat", "user_session_purchase",
                "free_reservation", "free_session_quota", "ticket_refund_request", "ticket_refund_item", "gift_inventory_operation", "gift_inventory_operation_item")
                .stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1")).toList();
    }
    private void consistent() { RefundInvariantAssertions.assertConsistent(jdbc); FreeReservationInvariantAssertions.assertConsistent(jdbc); }
}
