package com.limitactivitysystem.activity.integration;

import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.ticketorder.model.TicketOrder;
import com.limitactivitysystem.ticketorder.service.TicketOrderCloseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Small correctness experiment with real HTTP, real transactions and observed InnoDB lock waits. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import(TicketOrderSmallConcurrencyMySqlIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class TicketOrderSmallConcurrencyMySqlIntegrationTest extends PaymentResourceTestSupport {
    @LocalServerPort private int port;
    @Autowired private TicketOrderCloseService closing;
    private ActivitySession session;
    private SessionZone zone;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    enum Scenario { PAY_VS_CANCEL, PAY_VS_CLOSE_BEFORE_EXPIRY, PAY_VS_CLOSE_AFTER_EXPIRY }
    enum Action { PAY, CANCEL, CLOSE }

    @BeforeEach void createPublishedScope() throws Exception {
        var activity = insertDraft(publisher, "PAID");
        jdbc.update("""
                UPDATE activity SET open_time = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 HOUR),
                    close_time = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 HOUR) WHERE activity_id = ?
                """, activity.getActivityId());
        session = insertSession(activity);
        zone = insertZone(session, "PAID", 10, 10);
        publish(activity, publisher).andExpect(status().isNoContent());
    }

    static Stream<Arguments> scenarios() {
        int[] levels = Arrays.stream(System.getProperty("day8.concurrency.levels", "20,50").split(","))
                .map(String::trim).mapToInt(Integer::parseInt).distinct().toArray();
        if (levels.length == 0 || Arrays.stream(levels).anyMatch(level -> level < 20 || level > 50 || level % 2 != 0)) {
            throw new IllegalArgumentException("Use even concurrency levels from 20 through 50");
        }
        return Arrays.stream(levels).boxed().flatMap(level -> Arrays.stream(Scenario.values())
                .map(scenario -> Arguments.of(level, scenario)));
    }

    @ParameterizedTest(name = "{0} participants: {1}") @MethodSource("scenarios")
    void competingOperationsHaveOneLegalTerminalState(int concurrency, Scenario scenario) throws Exception {
        prepareConnections(concurrency + 2);
        var order = createOrder();
        if (scenario == Scenario.PAY_VS_CLOSE_AFTER_EXPIRY) {
            jdbc.update("UPDATE ticket_order SET created_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 16 MINUTE) WHERE order_id = ?", order.getOrderId());
        }
        String token = bearer(buyer);
        var rootLocked = new CountDownLatch(1);
        var releaseRoot = new CountDownLatch(1);
        var ready = new CountDownLatch(concurrency);
        var start = new CountDownLatch(1);
        var holderConnection = new AtomicLong();
        var holderExecutor = Executors.newSingleThreadExecutor();
        var clients = Executors.newFixedThreadPool(concurrency);
        List<Future<Outcome>> futures = new ArrayList<>();
        long started = System.nanoTime();
        int observedWaiters;
        // An early expiry UPDATE may reject its time predicate without waiting for the row lock.
        int requiredWaiters = scenario == Scenario.PAY_VS_CLOSE_BEFORE_EXPIRY ? concurrency / 2 : concurrency;
        List<Outcome> outcomes = new ArrayList<>();
        try {
            var holder = holderExecutor.submit(() -> tx(() -> {
                orders.findByIdForUpdate(order.getOrderId());
                holderConnection.set(connectionId()); rootLocked.countDown();
                awaitGate(releaseRoot, 25);
                return true;
            }));
            awaitGate(rootLocked, 10);
            for (int i = 0; i < concurrency; i++) {
                Action action = i % 2 == 0 ? Action.PAY : scenario == Scenario.PAY_VS_CANCEL ? Action.CANCEL : Action.CLOSE;
                futures.add(clients.submit(() -> {
                    ready.countDown(); awaitGate(start, 10);
                    return execute(action, order.getOrderId(), token);
                }));
            }
            awaitGate(ready, 10); start.countDown();
            // The matching-state races require every participant to reach a real database lock wait.
            observedWaiters = awaitOrderWaiters(holderConnection.get(), requiredWaiters);
            releaseRoot.countDown();
            for (var future : futures) { outcomes.add(future.get(30, TimeUnit.SECONDS)); }
            assertThat(holder.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            start.countDown(); releaseRoot.countDown();
            clients.shutdownNow(); holderExecutor.shutdownNow();
            assertThat(clients.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            assertThat(holderExecutor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        var finalState = assertLegalResult(order, scenario, outcomes);
        PaymentInvariantAssertions.assertConsistent(jdbc);
        evidence("day8-concurrency-" + scenario.name().toLowerCase(Locale.ROOT) + "-c" + concurrency,
                Map.of("scenario", scenario.name(), "participants", concurrency, "paymentRequests", concurrency / 2,
                        "competingOperations", concurrency / 2, "observedSimultaneousDatabaseWaiters", observedWaiters,
                        "transport", scenario == Scenario.PAY_VS_CANCEL ? "HTTP pay + HTTP cancel" : "HTTP pay + closeExpiredOrder Service",
                        "durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                        "outcomes", outcomes, "finalState", finalState, "paymentInvariants", "PASS"));
    }

    private Outcome execute(Action action, Long orderId, String token) throws Exception {
        long started = System.nanoTime();
        if (action == Action.CLOSE) {
            boolean changed = closing.closeExpiredOrder(orderId);
            return new Outcome(action, null, null, null, List.of(), changed, elapsedMillis(started));
        }
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ticket-orders/" + orderId
                        + (action == Action.PAY ? "/pay" : "/cancel")))
                .timeout(Duration.ofSeconds(30)).header("Authorization", token).POST(HttpRequest.BodyPublishers.noBody()).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        var body = objectMapper.readTree(response.body());
        List<Long> ticketIds = new ArrayList<>();
        body.path("tickets").forEach(ticket -> ticketIds.add(ticket.path("ticketId").asLong()));
        return new Outcome(action, response.statusCode(), body.path("code").asText(null), body.path("status").asText(null),
                ticketIds, null, elapsedMillis(started));
    }

    private Map<String, Object> assertLegalResult(TicketOrder order, Scenario scenario, List<Outcome> outcomes) {
        var stored = orders.findById(order.getOrderId());
        assertThat(stored.getStatus()).isIn("PAID", "CLOSED");
        int tickets = jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Integer.class);
        int soldSeats = jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE allocation_status = 'SOLD'", Integer.class);
        int validCount = jdbc.queryForObject("SELECT COALESCE(SUM(valid_count), 0) FROM user_session_purchase", Integer.class);
        int remains = zoneMapper.findById(zone.getZoneId()).getRemainsAmount();
        if ("PAID".equals(stored.getStatus())) {
            assertThat(scenario).isNotEqualTo(Scenario.PAY_VS_CLOSE_AFTER_EXPIRY);
            assertThat(stored.getPaidAt()).isNotNull().isBefore(stored.getExpiresAt());
            assertThat(stored.getClosedReason()).isNull();
            assertThat(tickets).isEqualTo(1); assertThat(soldSeats).isEqualTo(1);
            assertThat(validCount).isEqualTo(1); assertThat(remains).isEqualTo(9);
            Long ticketId = jdbc.queryForObject("SELECT ticket_id FROM ticket", Long.class);
            for (var outcome : outcomes) {
                if (outcome.action() == Action.PAY) {
                    assertThat(outcome.httpStatus()).isEqualTo(200); assertThat(outcome.status()).isEqualTo("PAID");
                    assertThat(outcome.ticketIds()).containsExactly(ticketId);
                } else if (outcome.action() == Action.CANCEL) {
                    assertThat(outcome.httpStatus()).isEqualTo(409); assertThat(outcome.code()).isEqualTo("ORDER_NOT_PENDING");
                } else { assertThat(outcome.closed()).isFalse(); }
            }
        } else {
            assertThat(scenario).isNotEqualTo(Scenario.PAY_VS_CLOSE_BEFORE_EXPIRY);
            assertThat(stored.getClosedReason()).isEqualTo(scenario == Scenario.PAY_VS_CANCEL ? "USER_CANCELLED" : "EXPIRED");
            assertThat(stored.getPaidAt()).isNull();
            assertThat(tickets).isZero(); assertThat(soldSeats).isZero(); assertThat(validCount).isZero();
            assertThat(remains).isEqualTo(10);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_session_purchase", Integer.class)).isZero();
            long changed = outcomes.stream().filter(result -> Boolean.TRUE.equals(result.closed())
                    || (result.action() == Action.CANCEL && Integer.valueOf(200).equals(result.httpStatus()))).count();
            assertThat(changed).isEqualTo(1);
            for (var outcome : outcomes) {
                if (outcome.action() == Action.PAY) {
                    assertThat(outcome.httpStatus()).isEqualTo(409);
                    assertThat(outcome.code()).isIn("ORDER_NOT_PENDING", "ORDER_EXPIRED");
                } else if (outcome.action() == Action.CANCEL) {
                    assertThat(outcome.httpStatus()).isIn(200, 409);
                    if (outcome.httpStatus() == 200) { assertThat(outcome.status()).isEqualTo("CLOSED"); }
                    else { assertThat(outcome.code()).isEqualTo("ORDER_NOT_PENDING"); }
                }
            }
        }
        return Map.of("orderId", order.getOrderId(), "status", stored.getStatus(), "closedReason",
                stored.getClosedReason() == null ? "NONE" : stored.getClosedReason(), "tickets", tickets, "soldSeats", soldSeats,
                "validPurchaseCount", validCount, "remainingInventory", remains);
    }

    private int awaitOrderWaiters(long holder, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        int observed;
        do {
            observed = jdbc.queryForObject("""
                    SELECT COUNT(DISTINCT w.REQUESTING_THREAD_ID)
                    FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.threads b ON b.THREAD_ID = w.BLOCKING_THREAD_ID
                    JOIN performance_schema.data_locks l
                      ON l.ENGINE = w.ENGINE AND l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                    WHERE b.PROCESSLIST_ID = ? AND l.OBJECT_SCHEMA = DATABASE() AND l.OBJECT_NAME = 'ticket_order'
                    """, Integer.class, holder);
            if (observed >= expected) { return observed; }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        } while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted());
        throw new AssertionError("Expected " + expected + " simultaneous database waiters, observed " + observed);
    }

    private void prepareConnections(int count) throws Exception {
        // Pool growth and authentication finish before any participant holds an Order lock.
        List<Connection> borrowed = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) { borrowed.add(jdbc.getDataSource().getConnection()); }
        } finally {
            for (var connection : borrowed) { connection.close(); }
        }
    }

    private TicketOrder createOrder() throws Exception {
        var seat = seatMapper.findByZoneId(zone.getZoneId()).get(0);
        var response = postJson("/ticket-orders", buyer, Map.of("requestId", UUID.randomUUID().toString(),
                "sessionId", session.getActivitySessionId(), "items", List.of(Map.of("zoneId", zone.getZoneId(),
                        "allocationMode", "SELECT_SEAT", "requestedSeatId", seat.getSeatId(), "quantity", 1,
                        "acceptNonAdjacent", false)))).andExpect(status().isCreated()).andReturn().getResponse();
        return orders.findById(objectMapper.readTree(response.getContentAsString()).get("orderId").asLong());
    }

    private static long elapsedMillis(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }

    private static void awaitGate(CountDownLatch gate, int seconds) {
        try { assertThat(gate.await(seconds, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }

    record Outcome(Action action, Integer httpStatus, String code, String status, List<Long> ticketIds, Boolean closed, long durationMs) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean IsolatedQualificationMySqlDataSource dataSource(DataSourceProperties properties) throws Exception {
            // Up to 50 contenders, one controlled holder and one observer; other tests retain their six-connection pool.
            return new IsolatedQualificationMySqlDataSource(properties, 54);
        }
    }
}
