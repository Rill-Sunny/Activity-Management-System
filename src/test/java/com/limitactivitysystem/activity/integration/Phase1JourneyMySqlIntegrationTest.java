package com.limitactivitysystem.activity.integration;

import com.fasterxml.jackson.databind.*;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.ticketorder.service.TicketOrderCloseService;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import({GiftInventorySchemaMySqlIntegrationTest.DatabaseConfiguration.class, Phase1JourneyMySqlIntegrationTest.ProbeConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class Phase1JourneyMySqlIntegrationTest {
    static final List<String> SCRIPTS = List.of("invariants.sql", "orphans.sql", "reservation_invariants.sql", "refund_invariants.sql", "gift_invariants.sql");
    static final List<String> TABLES = List.of("gift_inventory_operation_item", "gift_inventory_operation", "ticket_refund_item",
            "ticket_refund_request", "free_reservation", "free_session_quota", "whitelist_member", "whitelist_config", "ticket",
            "ticket_order_item", "ticket_order", "user_session_purchase", "session_seat", "session_zone", "activity_session", "activity", "app_user");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired TicketOrderCloseService closing;
    @Autowired Observation observation;
    private final Map<String, String> tokens = new HashMap<>();
    private final Map<String, Long> users = new HashMap<>();
    private final List<Map<String, Object>> audits = new ArrayList<>();
    private final List<Long> selectedSeats = new ArrayList<>(), selectedZones = new ArrayList<>();
    private JsonNode fixture;
    private Long paidActivity, freeActivity, selected, automatic, autoZone, giftZone, freeOne, freeTwo;

    @BeforeEach void rebuildTheFixedWorldThroughRealManagementAndLoginApis() throws Exception {
        // This DataSource owns a disposable random schema, never the developer's application database.
        observation.active.remove();
        for (String table : TABLES) { jdbc.update("DELETE FROM " + table); }
        fixture = json.readTree(Files.readString(Path.of("tests", "fixtures", "phase1.json")));
        for (var name : fixture.path("publishers")) {
            jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES (?,?,'PUBLISHER')", name.asText(), passwords.encode(fixture.path("password").asText()));
        }
        for (var name : fixture.path("users")) {
            call(post("/auth/register"), null, Map.of("username", name.asText(), "password", fixture.path("password").asText()), 201);
        }
        for (String alias : List.of("publisher_a", "publisher_b", "user_a", "user_b", "user_c")) {
            String name = "phase1_" + alias;
            users.put(alias, jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, name));
            var login = call(post("/auth/login"), null, Map.of("username", name, "password", fixture.path("password").asText()), 200);
            tokens.put(alias, "Bearer " + login.path("accessToken").asText());
        }
        LocalDateTime now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class);
        paidActivity = activity("PAID", fixture.path("paid").path("name").asText(), now);
        freeActivity = activity("FREE", fixture.path("free").path("name").asText(), now);
        var paid = fixture.path("paid"); var select = paid.path("selected"); var auto = paid.path("automatic");
        selected = session(paidActivity, now, select.path("capacity").asInt(), "SELECT_SEAT", select.path("limit").asInt());
        automatic = session(paidActivity, now, auto.path("capacity").asInt(), "AUTO_ASSIGN", auto.path("limit").asInt());
        for (var price : select.path("prices")) {
            long zone = zone(selected, "PAID", price.decimalValue(), 1);
            selectedZones.add(zone); selectedSeats.add(seats(zone, 1).get(0));
        }
        giftZone = zone(selected, "GIFT", BigDecimal.ZERO, select.path("giftSeats").asInt()); seats(giftZone, select.path("giftSeats").asInt());
        autoZone = zone(automatic, "PAID", auto.path("price").decimalValue(), auto.path("paidSeats").asInt()); seats(autoZone, auto.path("paidSeats").asInt());
        long autoGift = zone(automatic, "GIFT", BigDecimal.ZERO, auto.path("giftSeats").asInt()); seats(autoGift, auto.path("giftSeats").asInt());
        freeOne = session(freeActivity, now, fixture.path("free").path("sessionCapacities").get(0).asInt(), null, 0);
        freeTwo = session(freeActivity, now, fixture.path("free").path("sessionCapacities").get(1).asInt(), null, 0);
        call(post("/publisher/activities/{id}/publish", paidActivity), "publisher_a", null, 204);
        call(post("/publisher/activities/{id}/publish", freeActivity), "publisher_a", null, 204);
        call(put("/publisher/activities/{id}/whitelist", paidActivity), "publisher_a", null, 200);
        for (String alias : List.of("user_a", "user_b", "user_c")) {
            call(post("/publisher/activities/{id}/whitelist/members", paidActivity), "publisher_a", Map.of("userId", users.get(alias)), 201);
        }
        audit("published-fixture");
        evidence("day13-environment", Map.of("java", System.getProperty("java.version"), "springBoot", org.springframework.boot.SpringBootVersion.getVersion(),
                "mysql", jdbc.queryForObject("SELECT VERSION()", String.class), "databaseTime", now,
                "migrations", jdbc.queryForList("SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank")));
    }

    @AfterEach void verifyCommittedStateBeforeTheNextFixture(TestInfo test) throws Exception {
        observation.active.remove(); audit("final");
        evidence("day13-" + test.getTestMethod().orElseThrow().getName() + "-" + test.getDisplayName().replaceAll("[^a-zA-Z0-9_-]", "_"),
                Map.of("test", test.getDisplayName(), "audits", audits, "schemaBusinessTables", 17,
                        "physicalForeignKeys", jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema = DATABASE()", Integer.class)));
    }

    @Test void completePaidAndFreeJourneysLeaveCorrectDatabaseFactsAndCompatibleLocks() throws Exception {
        var payTrace = new Probe(); observation.active.set(payTrace);
        long order = selectedOrder("journey-four"); pay(order); observation.active.remove(); audit("four-paid");
        assertOrderLocks(payTrace, "UserSessionPurchaseMapper.tryIncrease", "SessionZoneMapper.findByIdForUpdate", "SessionSeatMapper.occupySeat", "TicketMapper.insert");
        ascendingResources(payTrace,"SessionZoneMapper.findByIdForUpdate","SessionSeatMapper.occupySeat");
        var originalTickets = jdbc.queryForList("SELECT t.ticket_id, t.paid_price FROM ticket t JOIN ticket_order_item i ON i.order_item_id=t.order_item_id WHERE i.order_id=? ORDER BY t.ticket_id", order);
        assertThat(originalTickets.stream().map(row -> row.get("paid_price"))).containsExactly(new BigDecimal("200.00"), new BigDecimal("400.00"), new BigDecimal("800.00"), new BigDecimal("1200.00"));
        long refund = refund(order, List.of(((Number)originalTickets.get(1).get("ticket_id")).longValue(), ((Number)originalTickets.get(3).get("ticket_id")).longValue()));
        audit("accepted-refund-no-resource-release");
        var refundTrace = new Probe(); observation.active.set(refundTrace);
        call(post("/ticket-refunds/{id}/process", refund), "user_a", null, 200); observation.active.remove(); audit("partial-refund");
        assertOrderLocks(refundTrace, "UserSessionPurchaseMapper.findForUpdate", "SessionZoneMapper.findByIdForUpdate", "SessionSeatMapper.findByIdForUpdate", "RefundTicketMapper.lockTicket");
        ascendingResources(refundTrace,"SessionZoneMapper.findByIdForUpdate","SessionSeatMapper.findByIdForUpdate");
        assertThat(jdbc.queryForList("SELECT status FROM ticket ORDER BY ticket_id", String.class)).containsExactly("VALID", "REFUNDED", "VALID", "REFUNDED");
        assertThat(jdbc.queryForList("SELECT refund_amount FROM ticket_refund_item ORDER BY ticket_id", BigDecimal.class)).containsExactly(new BigDecimal("340.00"), new BigDecimal("1020.00"));
        assertThat(jdbc.queryForObject("SELECT status FROM ticket_order WHERE order_id=?", String.class, order)).isEqualTo("PARTIALLY_REFUNDED");
        var giftTrace = new Probe(); observation.active.set(giftTrace);
        var gift = call(post("/publisher/gift-zones/{id}/allocations", giftZone), "publisher_a", Map.of("requestId", "journey-gift", "quantity", 2), 201);
        observation.active.remove(); audit("gift-allocated");
        ordered(giftTrace, "SessionZoneMapper.findByIdForUpdate", "GiftInventoryMapper.occupySeat", "GiftOperationMapper.insert");
        ascendingResources(giftTrace,"SessionZoneMapper.findByIdForUpdate","GiftInventoryMapper.occupySeat");
        call(get("/publisher/gift-allocations/{id}", gift.path("operationId").asLong()), "publisher_a", null, 200);
        long autoOrder = autoOrder("user_b", "journey-auto", 2);
        call(post("/ticket-orders/{id}/pay", autoOrder), "user_b", null, 200); audit("auto-paid");
        long cancel = autoOrder("user_c", "journey-cancel", 1);
        call(post("/ticket-orders/{id}/cancel", cancel), "user_c", null, 200); audit("pending-cancelled");
        long expired = autoOrder("user_c", "journey-expired", 1);
        jdbc.update("UPDATE ticket_order SET created_at=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 16 MINUTE) WHERE order_id=?", expired);
        assertThat(closing.closeExpiredOrder(expired)).isTrue(); assertThat(closing.closeExpiredOrder(expired)).isFalse(); audit("expired-closed");
        var reservationTrace = new Probe(); observation.active.set(reservationTrace);
        long reservation = reserve("user_a", freeOne, 201); observation.active.remove();
        ordered(reservationTrace, "FreeSessionQuotaMapper.findBySessionIdForUpdate", "FreeReservationMapper.insertIfReservable");
        reserve("user_a", freeTwo, 201); reserve("user_a", freeOne, 409); audit("cross-session-booking");
        call(post("/reservations/{id}/cancel", reservation), "user_a", null, 200); audit("reservation-cancelled");
        reserve("user_b", freeOne, 201); reserve("user_a", freeOne, 201); audit("rebook-without-priority");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM free_reservation WHERE status='ACTIVE'", Integer.class)).isEqualTo(3);
        evidence("day13-full-journey", Map.of("originalTickets", originalTickets, "refundAmounts", List.of(340, 1020), "orderStatus", "PARTIALLY_REFUNDED",
                "gift", gift, "activeReservations", 3, "audits", audits));
        evidence("day13-lock-traces", Map.of("payment", payTrace.events, "refund", refundTrace.events, "gift", giftTrace.events, "reservation", reservationTrace.events));
    }

    @Test void demoABCRunsPaidFreeAndGiftContinuouslyAgainstCommittedDatabaseState() throws Exception {
        var published = call(get("/activities"), "user_a", null, 200);
        var visibleIds = new ArrayList<Long>(); published.forEach(activity -> visibleIds.add(activity.path("activityId").asLong()));
        assertThat(visibleIds).contains(paidActivity, freeActivity);
        assertThat(call(get("/activities/{id}", paidActivity), "user_a", null, 200).path("status").asText()).isEqualTo("PUBLISHED");
        long order = selectedOrder("journey-four");
        var payment = call(post("/ticket-orders/{id}/pay", order), "user_a", null, 200);
        assertThat(payment.path("tickets").size()).isEqualTo(4);
        var selectedTickets = new ArrayList<Long>();
        for (var ticket : payment.path("tickets")) {
            assertThat(ticket.path("status").asText()).isEqualTo("VALID");
            if (ticket.path("paidPrice").decimalValue().compareTo(new BigDecimal("400")) == 0
                    || ticket.path("paidPrice").decimalValue().compareTo(new BigDecimal("1200")) == 0) {
                selectedTickets.add(ticket.path("ticketId").asLong());
            }
        }
        assertThat(selectedTickets).hasSize(2); audit("demo-A-paid");
        long refund = refund(order, selectedTickets); audit("demo-A-pending-refund");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE allocation_status='SOLD'", Integer.class)).isEqualTo(4);
        var result = call(post("/ticket-refunds/{id}/process", refund), "user_a", null, 200);
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM ticket_order WHERE order_id=?", String.class, order)).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(jdbc.queryForList("SELECT refund_amount FROM ticket_refund_item ORDER BY ticket_id", BigDecimal.class))
                .containsExactly(new BigDecimal("340.00"), new BigDecimal("1020.00"));
        assertThat(jdbc.queryForList("SELECT status FROM ticket ORDER BY ticket_id", String.class)).containsExactly("VALID", "REFUNDED", "VALID", "REFUNDED");
        for (int i=0;i<4;i++) {
            boolean returned = i == 1 || i == 3;
            assertThat(jdbc.queryForObject("SELECT allocation_status FROM session_seat WHERE seat_id=?", String.class, selectedSeats.get(i))).isEqualTo(returned ? "AVAILABLE" : "SOLD");
            assertThat(jdbc.queryForObject("SELECT remains_amount FROM session_zone WHERE zone_id=?", Integer.class, selectedZones.get(i))).isEqualTo(returned ? 1 : 0);
        }
        audit("demo-A-partially-refunded");

        long first = reserve("user_a", freeOne, 201); audit("demo-B-reserved");
        call(post("/reservations/{id}/cancel", first), "user_a", null, 200); audit("demo-B-cancelled");
        long second = reserve("user_a", freeOne, 201); assertThat(second).isNotEqualTo(first);
        assertThat(jdbc.queryForList("SELECT status FROM free_reservation ORDER BY reservation_id", String.class)).containsExactly("CANCELLED", "ACTIVE");
        assertThat(jdbc.queryForObject("SELECT remaining FROM free_session_quota WHERE session_id=?", Integer.class, freeOne)).isEqualTo(1);
        audit("demo-B-rebooked");

        var gift = call(post("/publisher/gift-zones/{id}/allocations", giftZone), "publisher_a", Map.of("requestId", "demo-phase1-gift", "quantity", 1), 201);
        long allocated = gift.path("seatIds").get(0).asLong();
        assertThat(jdbc.queryForObject("SELECT allocation_status FROM session_seat WHERE seat_id=?", String.class, allocated)).isEqualTo("GIFT_ALLOCATED");
        assertThat(jdbc.queryForObject("SELECT remains_amount FROM session_zone WHERE zone_id=?", Integer.class, giftZone)).isEqualTo(1);
        assertThat(call(get("/publisher/gift-allocations/{id}", gift.path("operationId").asLong()), "publisher_a", null, 200)).isEqualTo(gift);
        var beforeRejection = resources();
        var giftSeats = jdbc.queryForList("SELECT seat_id FROM session_seat WHERE zone_id=? ORDER BY seat_id", Long.class, giftZone);
        for (long seat : giftSeats) {
            var item = Map.of("zoneId", giftZone, "allocationMode", "SELECT_SEAT", "requestedSeatId", seat, "quantity", 1, "acceptNonAdjacent", false);
            var denied = call(post("/ticket-orders"), "user_a", Map.of("requestId", "demo-gift-rejected-" + seat, "sessionId", selected, "items", List.of(item)), 400);
            assertThat(denied.path("code").asText()).isEqualTo("GIFT_ZONE_NOT_PURCHASABLE");
        }
        assertThat(resources()).isEqualTo(beforeRejection);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(5);
        audit("demo-C-public-gift-rejected");
        evidence("day13-demo-abc", Map.of("activityId", paidActivity, "orderId", order, "payment", payment,
                "refund", result, "freeSessionId", freeOne, "cancelledReservationId", first, "activeReservationId", second,
                "gift", gift, "publicGiftRejection", Map.of("http", 400, "code", "GIFT_ZONE_NOT_PURCHASABLE"), "audits", audits));
    }

    @Test void permissionMatrixRejectsOtherOwnersDisabledAccountsAndForgedActors() throws Exception {
        long order = autoOrder("user_a", "protected-order", 1);
        for (var request : List.of(get("/ticket-orders/{id}", order), post("/ticket-orders/{id}/pay", order), post("/ticket-orders/{id}/cancel", order))) {
            call(request, "user_b", null, 403);
        }
        for (String actor : List.of("publisher_b", "user_a")) {
            call(post("/publisher/activities/{id}/sessions", paidActivity), actor, sessionBody(selected, "SELECT_SEAT", 6, 4), 403);
            call(post("/publisher/sessions/{id}/zones", selected), actor, Map.of("name", "forbidden", "zoneType", "GIFT", "price", 0, "totalAmount", 1), 403);
            call(post("/publisher/zones/{id}/seats", giftZone), actor, seatBody(1), 403);
            call(put("/publisher/activities/{id}/whitelist", paidActivity), actor, null, 403);
            call(post("/publisher/gift-zones/{id}/allocations", giftZone), actor, Map.of("requestId", "forbidden", "quantity", 1), 403);
            call(post("/publisher/activities/{id}/publish", paidActivity), actor, null, 403);
        }
        var forged = new HashMap<>(autoBody("forged", 1)); forged.put("userId", users.get("user_b"));
        call(post("/ticket-orders"), "user_a", forged, 400);
        call(post("/ticket-orders/{id}/pay", order), "user_a", Map.of("userId", users.get("user_b")), 400);
        call(post("/publisher/gift-zones/{id}/allocations", giftZone), "publisher_a", Map.of("requestId", "forged", "quantity", 1, "publisherId", users.get("publisher_b")), 400);
        jdbc.update("UPDATE app_user SET status='DISABLED' WHERE id IN (?,?)", users.get("user_a"), users.get("publisher_a"));
        call(post("/ticket-orders/{id}/pay", order), "user_a", null, 401);
        call(post("/sessions/{id}/reservations", freeOne), "user_a", null, 401);
        call(post("/publisher/gift-zones/{id}/allocations", giftZone), "publisher_a", Map.of("requestId", "disabled", "quantity", 1), 401);
        call(put("/publisher/activities/{id}/whitelist", paidActivity), "publisher_a", null, 401);
        audit("permission-denials");
    }

    @Test void at05TwoThreeTicketOrdersCannotExceedTheAccountLimitOfFour() throws Exception {
        long a=autoOrder("user_a", "at05-A", 3), b=autoOrder("user_a", "at05-B", 3);
        var statuses=concurrentPayments(a, "user_a", b, "user_a");
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("SELECT valid_count FROM user_session_purchase WHERE user_id=? AND session_id=?", Integer.class, users.get("user_a"), automatic)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE status='VALID'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT remains_amount FROM session_zone WHERE zone_id=?", Integer.class, autoZone)).isEqualTo(3);
        audit("at05-quota-race"); evidence("day13-at05-quota-race", Map.of("limit",4,"quantityPerOrder",3,"statuses",statuses,"validCount",3,"validTickets",3,"audits",audits));
    }

    @Test void at07TwoPaymentsCompeteForOneRealPaidSeat() throws Exception {
        var now=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)",LocalDateTime.class);
        long activity=activity("PAID","Phase 1 last stock",now);
        long session=session(activity,now,1,"AUTO_ASSIGN",1), zone=zone(session,"PAID",BigDecimal.TEN,1); seats(zone,1);
        call(post("/publisher/activities/{id}/publish",activity),"publisher_a",null,204);
        var body=Map.of("sessionId",session,"items",List.of(Map.of("zoneId",zone,"allocationMode","AUTO_ASSIGN","quantity",1,"acceptNonAdjacent",true)));
        var first=new HashMap<String,Object>(body);first.put("requestId","at07-A");
        var second=new HashMap<String,Object>(body);second.put("requestId","at07-B");
        long a=call(post("/ticket-orders"),"user_a",first,201).path("orderId").asLong();
        long b=call(post("/ticket-orders"),"user_b",second,201).path("orderId").asLong();
        var statuses=concurrentPayments(a,"user_a",b,"user_b"); assertThat(statuses).containsExactlyInAnyOrder(200,409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE status='VALID'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT remains_amount FROM session_zone WHERE zone_id=?",Integer.class,zone)).isZero();
        audit("at07-last-stock"); evidence("day13-at07-last-stock",Map.of("statuses",statuses,"validTickets",1,"remaining",0,"audits",audits));
    }

    private List<Integer> concurrentPayments(long a,String actorA,long b,String actorB) throws Exception {
        var workers=java.util.concurrent.Executors.newFixedThreadPool(2);var barrier=new java.util.concurrent.CyclicBarrier(2);
        try {
            var one=workers.submit(() -> {barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);return mvc.perform(post("/ticket-orders/{id}/pay",a).header(HttpHeaders.AUTHORIZATION,tokens.get(actorA))).andReturn().getResponse().getStatus();});
            var two=workers.submit(() -> {barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);return mvc.perform(post("/ticket-orders/{id}/pay",b).header(HttpHeaders.AUTHORIZATION,tokens.get(actorB))).andReturn().getResponse().getStatus();});
            return List.of(one.get(15,java.util.concurrent.TimeUnit.SECONDS),two.get(15,java.util.concurrent.TimeUnit.SECONDS));
        } finally {workers.shutdownNow();assertThat(workers.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    }

    @ParameterizedTest @ValueSource(strings = {"UserSessionPurchaseMapper.tryIncrease", "SessionZoneMapper.decreaseStock", "SessionSeatMapper.occupySeat", "TicketMapper.insert",
            "UserSessionPurchaseMapper.tryDecrease", "SessionZoneMapper.restoreStock", "SessionSeatMapper.releaseSold", "FreeSessionQuotaMapper.takeOne", "GiftInventoryMapper.occupySeat"})
    void failuresAfterRealSqlRollBackAllResourcesBeforeReconciliation(String point) throws Exception {
        var probe = new Probe(); probe.failAfter = point;
        boolean refund = point.endsWith("tryDecrease") || point.endsWith("restoreStock") || point.endsWith("releaseSold");
        long order = 0, request = 0;
        if (!point.startsWith("Free") && !point.startsWith("Gift")) { order = selectedOrder("fault-order"); }
        if (refund) { pay(order); request = refund(order, List.of(jdbc.queryForObject("SELECT MIN(ticket_id) FROM ticket", Long.class))); }
        var before = resources(); observation.active.set(probe);
        try {
            if (point.startsWith("Free")) {
                assertThatThrownBy(() -> call(post("/sessions/{id}/reservations", freeOne), "user_a", null, 500)).hasRootCauseInstanceOf(SQLException.class);
            }
            else if (point.startsWith("Gift")) { call(post("/publisher/gift-zones/{id}/allocations", giftZone), "publisher_a", Map.of("requestId", "fault-gift", "quantity", 2), 500); }
            else if (refund) { call(post("/ticket-refunds/{id}/process", request), "user_a", null, 500); }
            else {
                long target = order;
                assertThatThrownBy(() -> call(post("/ticket-orders/{id}/pay", target), "user_a", null, 500)).hasRootCauseInstanceOf(SQLException.class);
            }
        } finally { observation.active.remove(); }
        assertThat(probe.injected).isTrue(); assertThat(resources()).isEqualTo(before); audit("rollback-" + point);
        evidence("day13-rollback-" + point, Map.of("injectedAfterSuccessfulSql", point, "resourceSnapshotUnchanged", true, "audits", audits));
    }

    @Test void orphanAndLimitAuditRegressionsExposeThePreviouslyMissedConditions() throws Exception {
        jdbc.update("INSERT INTO user_session_purchase(user_id,session_id,valid_count) VALUES (?,?,0)", Long.MAX_VALUE, selected);
        try {
            assertThat(inspect(List.of("refund_invariants.sql", "reservation_invariants.sql", "gift_invariants.sql"))).isEmpty();
            var detected = inspect(List.of("orphans.sql"));
            assertThat(detected.stream().map(row -> row.get("violation"))).contains("COUNTER_USER_SESSION");
            evidence("day13-bug001-orphan-counter", Map.of("legacyScriptsDetected", 0, "newFindings", detected));
        } finally { jdbc.update("DELETE FROM user_session_purchase WHERE user_id=?", Long.MAX_VALUE); }
        long order = selectedOrder("audit-limit"); pay(order);
        jdbc.update("UPDATE activity_session SET max_tickets_per_user=2 WHERE activity_session_id=?", selected);
        try {
            assertThat(inspect(List.of("refund_invariants.sql", "reservation_invariants.sql", "gift_invariants.sql"))).isEmpty();
            var detected = inspect(List.of("invariants.sql"));
            assertThat(detected.stream().map(row -> row.get("violation"))).contains("PURCHASE_LIMIT");
            evidence("day13-bug002-purchase-limit", Map.of("legacyScriptsDetected", 0, "newFindings", detected));
        } finally { jdbc.update("UPDATE activity_session SET max_tickets_per_user=4 WHERE activity_session_id=?", selected); }
    }

    private long activity(String type, String name, LocalDateTime now) throws Exception {
        return call(post("/publisher/activities"), "publisher_a", Map.of("name", name, "type", type,
                "openTime", now.minusDays(fixture.path("activityOpenDaysBefore").asInt()), "closeTime", now.plusDays(fixture.path("activityCloseDaysAfter").asInt())), 201).path("activityId").asLong();
    }
    private long session(long activity, LocalDateTime now, int capacity, String mode, int limit) throws Exception {
        var body = sessionBody(0L, mode, capacity, limit);
        return call(post("/publisher/activities/{id}/sessions", activity), "publisher_a", body, 201).path("activitySessionId").asLong();
    }
    private Map<String, Object> sessionBody(Long ignored, String mode, int capacity, int limit) {
        var now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class);
        var body = new HashMap<String, Object>(); body.put("capacity", capacity);
        body.put("startTime", now.plusDays(fixture.path("sessionStartDaysAfter").asInt())); body.put("endTime", now.plusDays(fixture.path("sessionStartDaysAfter").asInt()).plusHours(2));
        if (mode != null) { body.put("seatMode", mode); body.put("maxTicketsPerUser", limit); } return body;
    }
    private long zone(long session, String type, BigDecimal price, int quantity) throws Exception {
        return call(post("/publisher/sessions/{id}/zones", session), "publisher_a", Map.of("name", type + "-" + price, "zoneType", type, "price", price, "totalAmount", quantity), 201).path("zoneId").asLong();
    }
    private Map<String, Object> seatBody(int count) { return Map.of("seats", java.util.stream.IntStream.rangeClosed(1, count).mapToObj(i -> Map.of("rowNo", 1, "seatNo", i, "allocationPriority", i)).toList()); }
    private List<Long> seats(long zone, int count) throws Exception {
        var result = call(post("/publisher/zones/{id}/seats", zone), "publisher_a", seatBody(count), 201);
        var ids = new ArrayList<Long>(); result.path("seatIds").forEach(id -> ids.add(id.asLong())); return ids;
    }
    private long selectedOrder(String key) throws Exception {
        var items = new ArrayList<Map<String, Object>>();
        for (int i=0;i<selectedSeats.size();i++) { items.add(Map.of("zoneId", selectedZones.get(i), "allocationMode", "SELECT_SEAT", "quantity", 1, "requestedSeatId", selectedSeats.get(i), "acceptNonAdjacent", false)); }
        return call(post("/ticket-orders"), "user_a", Map.of("requestId", key, "sessionId", selected, "items", items), 201).path("orderId").asLong();
    }
    private Map<String, Object> autoBody(String key, int quantity) { return Map.of("requestId", key, "sessionId", automatic, "items", List.of(Map.of("zoneId", autoZone, "allocationMode", "AUTO_ASSIGN", "quantity", quantity, "acceptNonAdjacent", true))); }
    private long autoOrder(String actor, String key, int quantity) throws Exception { return call(post("/ticket-orders"), actor, autoBody(key, quantity), 201).path("orderId").asLong(); }
    private void pay(long order) throws Exception { call(post("/ticket-orders/{id}/pay", order), "user_a", null, 200); }
    private long refund(long order, List<Long> tickets) throws Exception { return call(post("/ticket-refunds"), "user_a", Map.of("requestId", "journey-refund", "orderId", order, "ticketIds", tickets, "reason", "USER_REQUEST"), 201).path("refundId").asLong(); }
    private long reserve(String actor, long session, int expected) throws Exception { return call(post("/sessions/{id}/reservations", session), actor, null, expected).path("reservationId").asLong(); }
    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String actor, Object body, int expected) throws Exception {
        if (actor != null) { builder.header(HttpHeaders.AUTHORIZATION, tokens.get(actor)); }
        if (body != null) { builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)); }
        var response = mvc.perform(builder).andExpect(status().is(expected)).andReturn().getResponse();
        return response.getContentAsByteArray().length == 0 ? json.createObjectNode() : json.readTree(response.getContentAsByteArray());
    }
    private Map<String, Object> resources() {
        var result = new LinkedHashMap<String, Object>();
        for (String table : TABLES.subList(0, TABLES.indexOf("app_user"))) {
            if (!table.startsWith("ticket_refund")) { result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1")); }
        }
        return result;
    }
    private List<Map<String, Object>> inspect(List<String> scripts) throws Exception {
        var snapshot = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ); snapshot.setReadOnly(true);
        var sql = new LinkedHashMap<String, String>();
        for (String script : scripts) { sql.put(script, Files.readString(Path.of("tests", "sql", script)).lines().filter(line -> !line.stripLeading().startsWith("--")).collect(Collectors.joining("\n"))); }
        return snapshot.execute(status -> {
            var findings = new ArrayList<Map<String, Object>>();
            sql.forEach((name, text) -> {
                for (String statement : text.split(";")) {
                    if (!statement.isBlank()) { for (var row : jdbc.queryForList(statement)) { var finding=new LinkedHashMap<String,Object>(row); finding.put("script",name); findings.add(finding); } }
                }
            }); return findings;
        });
    }
    private void audit(String stage) throws Exception {
        var findings = inspect(SCRIPTS); assertThat(findings).as("Reconciliation at " + stage).isEmpty();
        audits.add(Map.of("stage", stage, "scripts", SCRIPTS, "violations", findings.size()));
    }
    private void evidence(String name, Object value) throws Exception {
        var path = Path.of("target", "verification", name + ".json"); Files.createDirectories(path.getParent()); json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }
    private void assertOrderLocks(Probe probe, String quota, String zone, String seat, String ticket) { ordered(probe, "TicketOrderMapper.findByIdForUpdate", quota, zone, seat, ticket); }
    private void ascendingResources(Probe probe,String zone,String seat) {
        var zoneRows=probe.events.stream().filter(event -> event.get("mapper").endsWith(zone)).toList();
        var seatRows=probe.events.stream().filter(event -> event.get("mapper").endsWith(seat)).toList();
        assertThat(zoneRows.stream().map(event -> Long.parseLong(event.get("zoneId")))).isSorted();
        assertThat(seatRows.stream().map(event -> Long.parseLong(event.get("seatId")))).isSorted();
        assertThat(probe.events.indexOf(zoneRows.get(zoneRows.size()-1))).isLessThan(probe.events.indexOf(seatRows.get(0)));
    }
    private void ordered(Probe probe, String... names) {
        int previous = -1;
        for (String name : names) { int index=-1; for(int i=0;i<probe.events.size();i++){if(probe.events.get(i).get("mapper").endsWith(name)){index=i;break;}} assertThat(index).as(name).isGreaterThan(previous); previous=index; }
    }
    static class Probe { String failAfter; boolean injected; final List<Map<String,String>> events = new ArrayList<>(); }
    @Intercepts({@Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class}), @Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class})})
    static class Observation implements Interceptor {
        final ThreadLocal<Probe> active = new ThreadLocal<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var probe=active.get(); var statement=(MappedStatement)invocation.getArgs()[0];
            if(probe != null){
                var event=new LinkedHashMap<String,String>();event.put("mapper",statement.getId());
                event.put("sql",statement.getBoundSql(invocation.getArgs()[1]).getSql().replaceAll("\\s+"," ").strip());
                if(invocation.getArgs()[1] instanceof Map<?,?> params){
                    for(String key:List.of("zoneId","seatId","ticketId")){if(params.containsKey(key)){event.put(key,String.valueOf(params.get(key)));}}
                }
                probe.events.add(event);
            }
            var result=invocation.proceed();
            if(probe != null && !probe.injected && probe.failAfter != null && statement.getId().endsWith(probe.failAfter)){
                assertThat(result).isEqualTo(1); probe.injected=true; throw new UncategorizedSQLException("Phase 1 fault injection", statement.getId(), new SQLException("Injected after successful SQL"));
            }
            return result;
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class ProbeConfiguration { @Bean Observation phase1Observation(){ return new Observation(); } }
}
