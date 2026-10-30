package com.limitactivitysystem.activity.integration;

import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.ticketorder.model.TicketOrder;
import com.limitactivitysystem.ticketorder.schedule.TicketOrderExpirationJob;
import com.limitactivitysystem.ticketorder.service.TicketOrderCloseService;
import com.limitactivitysystem.ticketorder.service.TicketPaymentService;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import(TicketOrderClosingMySqlIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class TicketOrderClosingMySqlIntegrationTest extends PaymentResourceTestSupport {
    @Autowired private TicketOrderCloseService closing;
    @Autowired private TicketPaymentService payments;
    @Autowired private OrderPauseInterceptor pauses;
    private ActivitySession session;
    private SessionZone zone;

    @BeforeEach void publishAnOpenPaidScope() throws Exception {
        var activity = insertDraft(publisher, "PAID");
        jdbc.update("""
                UPDATE activity SET open_time = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 HOUR),
                    close_time = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 HOUR) WHERE activity_id = ?
                """, activity.getActivityId());
        session = insertSession(activity);
        zone = insertZone(session, "PAID", 10, 10);
        publish(activity, publisher).andExpect(status().isNoContent());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void eitherBuyerRoleCancelsWithoutChangingAnyTicketResource(boolean publisherRole) throws Exception {
        AppUser actor = publisherRole ? publisher : buyer;
        var order = createOrder(actor);
        var before = resourceSnapshot(jdbc);
        mvc.perform(post("/ticket-orders/{id}/cancel", order.getOrderId()).header(HttpHeaders.AUTHORIZATION, bearer(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedReason").value("USER_CANCELLED"));
        mvc.perform(get("/ticket-orders/{id}", order.getOrderId()).header(HttpHeaders.AUTHORIZATION, bearer(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.closedReason").value("USER_CANCELLED"));
        assertClosed(order, "USER_CANCELLED");
        assertThat(resourceSnapshot(jdbc)).isEqualTo(before);
        assertThat(closing.closeExpiredOrder(order.getOrderId())).isFalse();
    }

    @Test void ownershipIsCheckedBeforeRevealingAClosedOrdersStateAndMissingOrdersReturn404() throws Exception {
        var order = createOrder(buyer);
        asUser(buyer, () -> closing.cancelOrder(order.getOrderId()));
        mvc.perform(post("/ticket-orders/{id}/cancel", order.getOrderId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherPublisher)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORDER_ACCESS_DENIED"));
        mvc.perform(post("/ticket-orders/{id}/cancel", Long.MAX_VALUE).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test void repeatedHttpCancellationHasNoAdditionalEffect() throws Exception {
        var order = createOrder(buyer);
        mvc.perform(post("/ticket-orders/{id}/cancel", order.getOrderId()).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isOk());
        var closed = orders.findById(order.getOrderId());
        var before = resourceSnapshot(jdbc);
        mvc.perform(post("/ticket-orders/{id}/cancel", order.getOrderId()).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_PENDING"));
        assertThat(orders.findById(order.getOrderId())).usingRecursiveComparison().isEqualTo(closed);
        assertThat(resourceSnapshot(jdbc)).isEqualTo(before);
    }

    @Test void cancellationAfterARealPaymentPreservesTicketsAndAllResources() throws Exception {
        var order = createOrder(buyer);
        asUser(buyer, () -> payments.pay(order.getOrderId()));
        var paid = orders.findById(order.getOrderId());
        var before = resourceSnapshot(jdbc);
        mvc.perform(post("/ticket-orders/{id}/cancel", order.getOrderId()).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_PENDING"));
        assertThat(orders.findById(order.getOrderId())).usingRecursiveComparison().isEqualTo(paid);
        assertThat(resourceSnapshot(jdbc)).isEqualTo(before);
        PaymentInvariantAssertions.assertConsistent(jdbc);
    }

    @ParameterizedTest @ValueSource(strings = {"PAID", "PARTIALLY_REFUNDED", "REFUNDED", "CLOSED"})
    void neitherCloserCanChangeAnOrderOutsidePendingPayment(String state) throws Exception {
        var order = createOrder(buyer);
        expire(order);
        if ("CLOSED".equals(state)) {
            asUser(buyer, () -> closing.cancelOrder(order.getOrderId()));
        } else {
            jdbc.update("UPDATE ticket_order SET status = ?, paid_at = created_at WHERE order_id = ?", state, order.getOrderId());
        }
        var before = orders.findById(order.getOrderId());
        var resourcesBefore = resourceSnapshot(jdbc);
        assertThat(closing.closeExpiredOrder(order.getOrderId())).isFalse();
        assertThatThrownBy(() -> asUser(buyer, () -> closing.cancelOrder(order.getOrderId())))
                .isInstanceOfSatisfying(TicketOrderException.class,
                        error -> assertThat(error.getErrorCode().name()).isEqualTo("ORDER_NOT_PENDING"));
        assertThat(orders.findById(order.getOrderId())).usingRecursiveComparison().isEqualTo(before);
        assertThat(resourceSnapshot(jdbc)).isEqualTo(resourcesBefore);
    }

    @Test void onlyExpiredPendingOrdersCloseAndRepeatedTriggersAreHarmless() throws Exception {
        var order = createOrder(buyer);
        assertThat(closing.closeExpiredOrder(order.getOrderId())).isFalse();
        assertThat(closing.closeExpiredOrder(Long.MAX_VALUE)).isFalse();
        expire(order);
        var before = resourceSnapshot(jdbc);
        var start = new CyclicBarrier(2);
        var first = async(() -> { start.await(10, TimeUnit.SECONDS); return closing.closeExpiredOrder(order.getOrderId()); });
        var second = async(() -> { start.await(10, TimeUnit.SECONDS); return closing.closeExpiredOrder(order.getOrderId()); });
        assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        assertThat(closing.closeExpiredOrder(order.getOrderId())).isFalse();
        assertClosed(order, "EXPIRED");
        assertThat(resourceSnapshot(jdbc)).isEqualTo(before);
    }

    @Test void scanningClosesOnlyOneBoundedBatchInExpiryOrder() throws Exception {
        List<Long> expiredIds = new ArrayList<>();
        LocalDateTime now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class);
        for (int i = 0; i < 5; i++) {
            var order = createOrder(buyer);
            jdbc.update("UPDATE ticket_order SET created_at = ? WHERE order_id = ?", now.minusMinutes(20).plusSeconds(i), order.getOrderId());
            expiredIds.add(order.getOrderId());
        }
        var fresh = createOrder(buyer);
        var before = resourceSnapshot(jdbc);
        assertThat(orders.findExpiredPendingOrderIds(2)).containsExactlyElementsOf(expiredIds.subList(0, 2));
        var job = new TicketOrderExpirationJob(orders, closing, 2);
        job.closeExpiredOrders();
        assertThat(orders.findExpiredPendingOrderIds(10)).containsExactlyElementsOf(expiredIds.subList(2, 5));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order WHERE status = 'CLOSED'", Integer.class)).isEqualTo(2);
        job.closeExpiredOrders(); job.closeExpiredOrders();
        assertThat(orders.findExpiredPendingOrderIds(10)).isEmpty();
        assertThat(orders.findById(fresh.getOrderId()).getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(resourceSnapshot(jdbc)).isEqualTo(before);
        var plan = jdbc.queryForList("""
                EXPLAIN SELECT order_id FROM ticket_order
                WHERE status = 'PENDING_PAYMENT' AND expires_at <= CURRENT_TIMESTAMP(3)
                ORDER BY expires_at, order_id LIMIT 2
                """);
        assertThat(plan.get(0).get("key")).isEqualTo("idx_ticket_order_expiry");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void closingFirstMakesPaymentWaitOnTheOrderAndRejectWithoutResources(boolean cancel) throws Exception {
        var order = createOrder(buyer);
        if (!cancel) { expire(order); }
        var before = resourceSnapshot(jdbc);
        var holder = pause(cancel ? ".closePendingForBuyer" : ".closePendingIfExpired", Timing.COMMIT);
        var waiter = pause(".findByIdForUpdate", Timing.ATTEMPT);
        var close = async(() -> observed(holder, () -> closeAsBuyer(order, cancel)));
        await(holder.reached);
        var pay = async(() -> observed(waiter, () -> asUser(buyer, () -> payments.pay(order.getOrderId()))));
        await(waiter.reached);
        awaitDatabaseLockWait(waiter.connectionId, holder.connectionId, "ticket_order");
        holder.release.countDown();
        assertThat(close.get(10, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> pay.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(TicketOrderException.class)
                .satisfies(failure -> assertThat(((TicketOrderException) failure.getCause()).getErrorCode().name()).isEqualTo("ORDER_NOT_PENDING"));
        assertClosed(order, cancel ? "USER_CANCELLED" : "EXPIRED");
        assertThat(resourceSnapshot(jdbc)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void paymentFirstMakesEitherCloserWaitAndPreservesAllPaidResources(boolean cancel) throws Exception {
        var order = createOrder(buyer);
        if (!cancel) { expireSoon(order); }
        var holder = pause(".markPaidIfPayable", Timing.COMMIT);
        var waiter = pause(cancel ? ".findByIdForUpdate" : ".closePendingIfExpired", Timing.ATTEMPT);
        var pay = async(() -> observed(holder, () -> asUser(buyer, () -> payments.pay(order.getOrderId()))));
        await(holder.reached);
        if (!cancel) { awaitExpiry(order); }
        var close = async(() -> observed(waiter, () -> closeAsBuyer(order, cancel)));
        await(waiter.reached);
        awaitDatabaseLockWait(waiter.connectionId, holder.connectionId, "ticket_order");
        holder.release.countDown();
        assertThat(pay.get(10, TimeUnit.SECONDS).status()).isEqualTo("PAID");
        if (cancel) {
            assertThatThrownBy(() -> close.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(TicketOrderException.class)
                    .satisfies(failure -> assertThat(((TicketOrderException) failure.getCause()).getErrorCode().name()).isEqualTo("ORDER_NOT_PENDING"));
        } else { assertThat(close.get(10, TimeUnit.SECONDS)).isFalse(); }
        var paid = orders.findById(order.getOrderId());
        assertThat(paid.getStatus()).isEqualTo("PAID");
        assertThat(paid.getClosedReason()).isNull();
        assertThat(paid.getPaidAt()).isBefore(paid.getExpiresAt());
        assertThat(zoneMapper.findById(zone.getZoneId()).getRemainsAmount()).isEqualTo(9);
        assertThat(purchases.findValidCount(buyer.getId(), session.getActivitySessionId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Integer.class)).isEqualTo(1);
        PaymentInvariantAssertions.assertConsistent(jdbc);
    }

    @Test void paymentCrossingExpiryRollsBackEveryResourceBeforeTheWaitingCloserSucceeds() throws Exception {
        var order = createOrder(buyer);
        expireSoon(order);
        var before = resourceSnapshot(jdbc);
        var holder = pause(".markPaidIfPayable", Timing.BEFORE);
        var waiter = pause(".closePendingIfExpired", Timing.ATTEMPT);
        var pay = async(() -> observed(holder, () -> asUser(buyer, () -> payments.pay(order.getOrderId()))));
        await(holder.reached);
        assertThat(holder.uncommittedTicketCount).isEqualTo(1);
        assertThat(holder.uncommittedStock).isEqualTo(9);
        awaitExpiry(order);
        var close = async(() -> observed(waiter, () -> closing.closeExpiredOrder(order.getOrderId())));
        await(waiter.reached);
        awaitDatabaseLockWait(waiter.connectionId, holder.connectionId, "ticket_order");
        holder.release.countDown();
        assertThatThrownBy(() -> pay.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(TicketOrderException.class)
                .satisfies(failure -> assertThat(((TicketOrderException) failure.getCause()).getErrorCode().name()).isEqualTo("ORDER_EXPIRED"));
        assertThat(close.get(10, TimeUnit.SECONDS)).isTrue();
        assertClosed(order, "EXPIRED");
        assertThat(resourceSnapshot(jdbc)).isEqualTo(before);
        PaymentInvariantAssertions.assertConsistent(jdbc);
    }

    @ParameterizedTest @CsvSource({"20:05:00.000,PAID", "20:12:59.999,PAID", "20:13:00.000,ORDER_EXPIRED"})
    void at08UsesTheOrdersDeadlineAfterActivitySalesClose(String paymentTime, String expected) throws Exception {
        configureAt08Times();
        var order = atDatabaseTime("19:58:00.000", () -> createOrder(buyer));
        assertThat(order.getCreatedAt()).isEqualTo(at08Time("19:58:00.000"));
        assertThat(order.getExpiresAt()).isEqualTo(at08Time("20:13:00.000"));
        var before = resourceSnapshot(jdbc);
        if ("PAID".equals(expected)) {
            var paid = atDatabaseTime(paymentTime, () -> asUser(buyer, () -> payments.pay(order.getOrderId())));
            assertThat(paid.status()).isEqualTo("PAID");
            assertThat(paid.paidAt()).isEqualTo(at08Time(paymentTime));
            assertThat(paid.tickets()).hasSize(1);
        } else {
            assertThatThrownBy(() -> atDatabaseTime(paymentTime, () -> asUser(buyer, () -> payments.pay(order.getOrderId()))))
                    .isInstanceOfSatisfying(TicketOrderException.class,
                            error -> assertThat(error.getErrorCode().name()).isEqualTo("ORDER_EXPIRED"));
            assertThat(resourceSnapshot(jdbc)).isEqualTo(before);
            assertThat(atDatabaseTime("20:12:59.999", () -> closing.closeExpiredOrder(order.getOrderId()))).isFalse();
            assertThat(atDatabaseTime("20:13:00.000", () -> closing.closeExpiredOrder(order.getOrderId()))).isTrue();
            assertClosed(order, "EXPIRED");
        }
        PaymentInvariantAssertions.assertConsistent(jdbc);
        evidence("at08-" + paymentTime.replace(":", "-").replace('.', '-'), Map.of(
                "createdAt", order.getCreatedAt(), "activityCloseTime", at08Time("20:00:00.000"),
                "expiresAt", order.getExpiresAt(), "paymentDatabaseTime", at08Time(paymentTime), "result", expected,
                "finalStatus", orders.findById(order.getOrderId()).getStatus()));
    }

    @ParameterizedTest @ValueSource(strings = {"CANCELLED", "STARTED"})
    void at08StillRejectsACancelledOrStartedSessionBeforeOrderExpiry(String change) throws Exception {
        configureAt08Times();
        var order = atDatabaseTime("19:58:00.000", () -> createOrder(buyer));
        if ("CANCELLED".equals(change)) {
            jdbc.update("UPDATE activity_session SET status = 'CANCELLED' WHERE activity_session_id = ?", session.getActivitySessionId());
        } else {
            jdbc.update("UPDATE activity_session SET start_time = ? WHERE activity_session_id = ?",
                    at08Time("20:05:00.000"), session.getActivitySessionId());
        }
        var before = resourceSnapshot(jdbc);
        assertThatThrownBy(() -> atDatabaseTime("20:05:00.000", () -> asUser(buyer, () -> payments.pay(order.getOrderId()))))
                .isInstanceOfSatisfying(TicketOrderException.class,
                        error -> assertThat(error.getErrorCode().name()).isEqualTo("SESSION_NOT_AVAILABLE"));
        assertThat(orders.findById(order.getOrderId()).getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(resourceSnapshot(jdbc)).isEqualTo(before);
        evidence("at08-session-" + change.toLowerCase(java.util.Locale.ROOT), Map.of("result", "SESSION_NOT_AVAILABLE",
                "paymentDatabaseTime", at08Time("20:05:00.000"), "expiresAt", order.getExpiresAt(), "resourcesUnchanged", true));
    }

    private void configureAt08Times() {
        jdbc.update("UPDATE activity SET open_time = ?, close_time = ? WHERE activity_id = ?",
                at08Time("19:00:00.000"), at08Time("20:00:00.000"), session.getActivityId());
        jdbc.update("UPDATE activity_session SET start_time = ?, end_time = ? WHERE activity_session_id = ?",
                at08Time("21:00:00.000"), at08Time("23:00:00.000"), session.getActivitySessionId());
    }

    private static LocalDateTime at08Time(String time) { return LocalDate.of(2026, 10, 28).atTime(LocalTime.parse(time)); }

    private <T> T atDatabaseTime(String time, Callable<T> work) throws Exception {
        pauses.databaseClock.set(at08Time(time));
        try { return work.call(); } finally { pauses.databaseClock.remove(); }
    }

    private TicketOrder createOrder(AppUser actor) throws Exception {
        var seat = seatMapper.findByZoneId(zone.getZoneId()).get(0);
        var response = postJson("/ticket-orders", actor, Map.of("requestId", UUID.randomUUID().toString(),
                "sessionId", session.getActivitySessionId(), "items", List.of(Map.of("zoneId", zone.getZoneId(),
                        "allocationMode", "SELECT_SEAT", "requestedSeatId", seat.getSeatId(), "quantity", 1,
                        "acceptNonAdjacent", false))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return orders.findById(objectMapper.readTree(response.getContentAsString()).get("orderId").asLong());
    }

    private boolean closeAsBuyer(TicketOrder order, boolean cancel) throws Exception {
        if (!cancel) { return closing.closeExpiredOrder(order.getOrderId()); }
        asUser(buyer, () -> closing.cancelOrder(order.getOrderId()));
        return true;
    }

    private <T> T asUser(AppUser actor, Callable<T> work) throws Exception {
        var token = jwtDecoder.decode(JwtTestTokens.token(jwtEncoder, actor.getId(), actor.getRole()));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticationConverter.convert(token));
        SecurityContextHolder.setContext(context);
        try { return work.call(); } finally { SecurityContextHolder.clearContext(); }
    }

    private void expire(TicketOrder order) {
        jdbc.update("UPDATE ticket_order SET created_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 16 MINUTE) WHERE order_id = ?", order.getOrderId());
    }

    private void expireSoon(TicketOrder order) {
        jdbc.update("UPDATE ticket_order SET created_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 898 SECOND) WHERE order_id = ?", order.getOrderId());
    }

    private void awaitExpiry(TicketOrder order) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT expires_at <= CURRENT_TIMESTAMP(3) FROM ticket_order WHERE order_id = ?",
                Boolean.class, order.getOrderId()))) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    private void assertClosed(TicketOrder order, String reason) {
        var closed = orders.findById(order.getOrderId());
        assertThat(closed.getStatus()).isEqualTo("CLOSED");
        assertThat(closed.getClosedReason()).isEqualTo(reason);
        assertThat(closed.getPaidAt()).isNull();
    }

    private static List<List<Map<String, Object>>> resourceSnapshot(JdbcTemplate jdbc) {
        return List.of("ticket_order_item", "session_zone", "session_seat", "user_session_purchase", "ticket")
                .stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1")).toList();
    }

    private Pause pause(String statement, Timing timing) { return new Pause(statement, timing, gate(), gate()); }

    private <T> T observed(Pause pause, Callable<T> work) throws Exception {
        pauses.current.set(pause);
        try { return work.call(); } finally { pauses.current.remove(); }
    }

    enum Timing { ATTEMPT, BEFORE, COMMIT }

    static class Pause {
        final String statement;
        final Timing timing;
        final CountDownLatch reached;
        final CountDownLatch release;
        long connectionId;
        int uncommittedTicketCount;
        int uncommittedStock;
        Pause(String statement, Timing timing, CountDownLatch reached, CountDownLatch release) {
            this.statement = statement; this.timing = timing; this.reached = reached; this.release = release;
        }
    }

    @Intercepts({@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
            @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})})
    static class OrderPauseInterceptor implements Interceptor {
        final ThreadLocal<Pause> current = new ThreadLocal<>();
        final ThreadLocal<LocalDateTime> databaseClock = new ThreadLocal<>();
        private final JdbcTemplate jdbc;
        OrderPauseInterceptor(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            if (databaseClock.get() != null && TransactionSynchronizationManager.isSynchronizationActive()) {
                // Test connections only: exercise real CURRENT_TIMESTAMP(3), including the final SQL predicate.
                jdbc.execute((ConnectionCallback<Void>) connection -> {
                    try (var statement = connection.prepareStatement("SET timestamp = UNIX_TIMESTAMP(?)")) {
                        statement.setString(1, databaseClock.get().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")));
                        statement.execute();
                    }
                    if (TransactionSynchronizationManager.getSynchronizations().stream().noneMatch(sync -> sync instanceof ClockReset)) {
                        TransactionSynchronizationManager.registerSynchronization(new ClockReset(connection));
                    }
                    return null;
                });
            }
            Pause pause = current.get();
            if (pause == null || !((MappedStatement) invocation.getArgs()[0]).getId().endsWith(pause.statement)) {
                return invocation.proceed();
            }
            pause.connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            if (pause.timing == Timing.ATTEMPT) { pause.reached.countDown(); }
            if (pause.timing == Timing.BEFORE) {
                pause.uncommittedTicketCount = jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Integer.class);
                pause.uncommittedStock = jdbc.queryForObject("SELECT SUM(remains_amount) FROM session_zone", Integer.class);
                pause.reached.countDown(); await(pause.release);
            }
            Object result = invocation.proceed();
            if (pause.timing == Timing.COMMIT) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {
                        pause.reached.countDown(); await(pause.release);
                    }
                });
            }
            return result;
        }
    }

    private record ClockReset(Connection connection) implements TransactionSynchronization {
        @Override public void afterCompletion(int status) {
            try (var statement = connection.createStatement()) { statement.execute("SET timestamp = DEFAULT"); }
            catch (SQLException exception) { throw new IllegalStateException("Could not reset the test connection clock", exception); }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean IsolatedQualificationMySqlDataSource dataSource(DataSourceProperties properties) throws Exception {
            return new IsolatedQualificationMySqlDataSource(properties);
        }
        @Bean OrderPauseInterceptor orderPauseInterceptor(JdbcTemplate jdbc) { return new OrderPauseInterceptor(jdbc); }
    }
}
