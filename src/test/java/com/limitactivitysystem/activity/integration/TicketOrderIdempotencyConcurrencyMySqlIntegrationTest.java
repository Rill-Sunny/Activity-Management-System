package com.limitactivitysystem.activity.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.limitactivitysystem.activity.mapper.ActivitySessionMapper;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.ticketorder.dto.CreateTicketOrderRequest;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.ticketorder.mapper.DatabaseTimeMapper;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import com.limitactivitysystem.ticketorder.service.TicketOrderService;
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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import(TicketOrderIdempotencyConcurrencyMySqlIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class TicketOrderIdempotencyConcurrencyMySqlIntegrationTest extends ActivityMySqlTestSupport {
    @Autowired private DatabaseTimeMapper time;
    @Autowired private TicketOrderService service;
    @Autowired private TicketOrderMapper orders;
    @Autowired private CreationRace observer;
    private ActivitySession session;
    private SessionZone firstZone;
    private SessionZone secondZone;
    private ExecutorService workers;
    private Race race;

    @BeforeEach
    void createPublishedScope() throws Exception {
        LocalDateTime now = time.currentTime();
        var activity = insertDraft(publisher, "PAID");
        activity.setOpenTime(now.minusHours(1));
        activity.setCloseTime(now.plusHours(1));
        jdbc.update("UPDATE activity SET open_time = ?, close_time = ? WHERE activity_id = ?",
                activity.getOpenTime(), activity.getCloseTime(), activity.getActivityId());
        session = insertSession(activity);
        jdbc.update("UPDATE activity_session SET seat_mode = 'AUTO_ASSIGN' WHERE activity_session_id = ?",
                session.getActivitySessionId());
        firstZone = insertZone(session, "PAID", 6, 6);
        secondZone = insertZone(session, "PAID", 4, 4);
        jdbc.update("UPDATE session_zone SET price = 20 WHERE zone_id = ?", secondZone.getZoneId());
        publish(activity, publisher).andExpect(status().isNoContent());
        workers = Executors.newFixedThreadPool(2);
        race = new Race();
    }

    @AfterEach
    void finishWorkersAndClearOnlyTheOwnedSchema() throws Exception {
        if (race != null) { race.lookups.reset(); race.inserts.reset(); }
        if (workers != null) {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        jdbc.update("DELETE FROM ticket_order_item");
        jdbc.update("DELETE FROM ticket_order");
        jdbc.update("DELETE FROM whitelist_member");
        jdbc.update("DELETE FROM whitelist_config");
    }

    @ParameterizedTest(name = "same payload={0}, existing outer snapshot={1}")
    @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void uniqueKeyLoserReadsTheWinnerOnlyAfterItsOwnCreationTransactionRolledBack(
            boolean samePayload, boolean outerSnapshot) throws Exception {
        String requestId = UUID.randomUUID().toString();
        var first = request(requestId, firstZone, 2); // 2 * 10.00
        var second = request(requestId, samePayload ? firstZone : secondZone, samePayload ? 2 : 1); // 1 * 20.00
        var before = inventorySnapshot();
        Attempt one = new Attempt(race);
        Attempt two = new Attempt(race);
        Future<Outcome> a = start(first, one, outerSnapshot);
        Future<Outcome> b = start(second, two, outerSnapshot);
        Outcome firstResult = a.get(20, TimeUnit.SECONDS);
        Outcome secondResult = b.get(20, TimeUnit.SECONDS);

        assertThat(List.of(firstResult.status(), secondResult.status()))
                .containsExactlyInAnyOrder(201, samePayload ? 200 : 409);
        Outcome created = firstResult.status() == 201 ? firstResult : secondResult;
        Outcome replayedOrConflicted = firstResult.status() == 201 ? secondResult : firstResult;
        if (samePayload) { assertThat(replayedOrConflicted.body()).isEqualTo(created.body()); }
        else { assertThat(replayedOrConflicted.body().path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT"); }

        assertThat(one.initialMiss).isTrue();
        assertThat(two.initialMiss).isTrue();
        assertThat(one.writerConnectionId).isPositive().isNotEqualTo(two.writerConnectionId);
        assertThat(List.of(one.writerCompletion, two.writerCompletion))
                .containsExactlyInAnyOrder(TransactionSynchronization.STATUS_COMMITTED,
                        TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(List.of(one, two).stream().filter(attempt -> attempt.duplicateKey).count()).isEqualTo(1);
        Attempt loser = one.duplicateKey ? one : two;
        assertThat(loser.lookupCalls.get()).isEqualTo(2);
        assertThat(loser.recoveryAfterRollback).isTrue();
        assertThat(loser.recoveryResource).isNotNull().isNotSameAs(loser.writerResource);
        assertThat(loser.recoveryRollbackOnly).isFalse();
        if (outerSnapshot) {
            assertThat(one.outerTransactionHealthy).isTrue();
            assertThat(two.outerTransactionHealthy).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order_item", Long.class)).isEqualTo(1);
        Long actorId = outerSnapshot ? publisher.getId() : buyer.getId();
        assertThat(orders.findByBuyerIdAndRequestId(actorId, requestId).getOrderId())
                .isEqualTo(created.body().path("orderId").asLong());
        assertThat(inventorySnapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"true,WINDOW_CLOSED", "false,WINDOW_CLOSED", "true,WHITELIST_CHANGED", "false,WHITELIST_CHANGED"})
    void aConcurrentWinnerIsReplayedEvenWhenTheLateAttemptFailsANewPurchasePrecheck(
            boolean samePayload, String change) throws Exception {
        var body = request(UUID.randomUUID().toString(), firstZone, 2);
        var winningBody = request((String) body.get("requestId"),
                samePayload ? firstZone : secondZone, samePayload ? 2 : 1);
        AtomicReference<JsonNode> winner = new AtomicReference<>();
        var before = inventorySnapshot();
        DelayedLookup delayed = new DelayedLookup(() -> {
            try {
                workers.submit(() -> {
                    var result = postJson("/ticket-orders", buyer, winningBody).andExpect(status().isCreated())
                            .andReturn().getResponse();
                    winner.set(objectMapper.readTree(result.getContentAsString()));
                    if (change.equals("WINDOW_CLOSED")) {
                        jdbc.update("UPDATE activity SET open_time = ?, close_time = ? WHERE activity_id = ?",
                                time.currentTime().minusHours(2), time.currentTime().minusHours(1), session.getActivityId());
                    } else {
                        jdbc.update("INSERT INTO whitelist_config (scope_type, scope_id) VALUES ('SESSION', ?)",
                                session.getActivitySessionId());
                    }
                    return null;
                }).get(10, TimeUnit.SECONDS);
            } catch (Exception exception) { throw new IllegalStateException(exception); }
        });
        observer.delayed.set(delayed);
        try {
            var response = postJson("/ticket-orders", buyer, body)
                    .andExpect(status().is(samePayload ? 200 : 409)).andReturn().getResponse();
            JsonNode actual = objectMapper.readTree(response.getContentAsString());
            if (samePayload) { assertThat(actual).isEqualTo(winner.get()); }
            else { assertThat(actual.path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT"); }
        } finally { observer.delayed.remove(); }
        assertThat(delayed.lookupCalls).isEqualTo(2);
        assertThat(delayed.writerCompletion).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(delayed.recoveryAfterRollback).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order_item", Long.class)).isEqualTo(1);
        assertThat(inventorySnapshot()).isEqualTo(before);
    }

    private Future<Outcome> start(Map<String, Object> body, Attempt attempt, boolean outerSnapshot) {
        return workers.submit(() -> {
            observer.current.set(attempt);
            try {
                if (!outerSnapshot) {
                    var response = postJson("/ticket-orders", buyer, body).andReturn().getResponse();
                    return new Outcome(response.getStatus(), objectMapper.readTree(response.getContentAsString()));
                }
                TransactionTemplate outer = new TransactionTemplate(transactionManager);
                outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                return outer.execute(transaction -> {
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order", Long.class)).isZero();
                    Outcome outcome;
                    try {
                        var request = objectMapper.convertValue(body, CreateTicketOrderRequest.class);
                        var result = asPublisher(() -> service.createOrder(request));
                        outcome = new Outcome(result.created() ? 201 : 200, objectMapper.valueToTree(result.order()));
                    } catch (TicketOrderException exception) {
                        outcome = new Outcome(exception.getErrorCode().getHttpStatus().value(),
                                objectMapper.valueToTree(Map.of("code", exception.getErrorCode().name())));
                    } catch (Exception exception) { throw new IllegalStateException(exception); }
                    assertThat(transaction.isRollbackOnly()).isFalse();
                    // The old caller snapshot stays old, but replay has already read the committed winner in a new TX.
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order", Long.class)).isZero();
                    attempt.outerTransactionHealthy = true;
                    return outcome;
                });
            } finally {
                observer.current.remove();
                SecurityContextHolder.clearContext();
            }
        });
    }

    private Map<String, Object> request(String requestId, SessionZone zone, int quantity) {
        return Map.of("requestId", requestId, "sessionId", session.getActivitySessionId(), "items", List.of(
                Map.of("zoneId", zone.getZoneId(), "allocationMode", "AUTO_ASSIGN", "quantity", quantity,
                        "acceptNonAdjacent", true)));
    }

    private Map<String, List<Map<String, Object>>> inventorySnapshot() {
        Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
        for (String table : List.of("session_zone", "session_seat", "user_session_purchase", "ticket")) {
            snapshot.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1"));
        }
        return snapshot;
    }

    record Outcome(int status, JsonNode body) { }
    static class Race {
        final CyclicBarrier lookups = new CyclicBarrier(2);
        final CyclicBarrier inserts = new CyclicBarrier(2);
    }
    static class Attempt {
        final Race race;
        final AtomicInteger lookupCalls = new AtomicInteger();
        volatile boolean initialMiss;
        volatile boolean duplicateKey;
        volatile long writerConnectionId;
        volatile int writerCompletion = -1;
        volatile Object writerResource;
        volatile Object recoveryResource;
        volatile boolean recoveryAfterRollback;
        volatile boolean recoveryRollbackOnly;
        volatile boolean outerTransactionHealthy;
        Attempt(Race race) { this.race = race; }
    }

    static class DelayedLookup {
        final Runnable commitWinner;
        int lookupCalls;
        int writerCompletion = -1;
        boolean recoveryAfterRollback;
        Object writerResource;
        DelayedLookup(Runnable commitWinner) { this.commitWinner = commitWinner; }
    }

    @Intercepts({
            @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
    })
    static class CreationRace implements Interceptor {
        final ThreadLocal<Attempt> current = new ThreadLocal<>();
        final ThreadLocal<DelayedLookup> delayed = new ThreadLocal<>();
        private final JdbcTemplate jdbc;
        CreationRace(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            String id = ((MappedStatement) invocation.getArgs()[0]).getId();
            DelayedLookup late = delayed.get();
            if (late != null) {
                if (id.equals(TicketOrderMapper.class.getName() + ".findByBuyerIdAndRequestId")) {
                    if (++late.lookupCalls == 1) {
                        Object result = invocation.proceed();
                        assertThat((List<?>) result).isEmpty();
                        late.commitWinner.run();
                        return result;
                    }
                    var resource = (ConnectionHolder) TransactionSynchronizationManager.getResource(jdbc.getDataSource());
                    late.recoveryAfterRollback = late.writerCompletion == TransactionSynchronization.STATUS_ROLLED_BACK
                            && resource != late.writerResource && !resource.isRollbackOnly();
                } else if (id.equals(ActivitySessionMapper.class.getName() + ".findById") && late.writerResource == null) {
                    late.writerResource = TransactionSynchronizationManager.getResource(jdbc.getDataSource());
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void afterCompletion(int status) { late.writerCompletion = status; }
                    });
                }
                return invocation.proceed();
            }
            Attempt attempt = current.get();
            if (attempt == null) { return invocation.proceed(); }
            if (id.equals(TicketOrderMapper.class.getName() + ".findByBuyerIdAndRequestId")) {
                if (attempt.lookupCalls.incrementAndGet() == 1) {
                    Object result = invocation.proceed();
                    assertThat((List<?>) result).isEmpty();
                    attempt.initialMiss = true;
                    attempt.race.lookups.await(10, TimeUnit.SECONDS);
                    return result;
                }
                ConnectionHolder resource = (ConnectionHolder) TransactionSynchronizationManager.getResource(jdbc.getDataSource());
                attempt.recoveryResource = resource;
                attempt.recoveryRollbackOnly = resource.isRollbackOnly();
                attempt.recoveryAfterRollback = attempt.writerCompletion == TransactionSynchronization.STATUS_ROLLED_BACK
                        && TransactionSynchronizationManager.isActualTransactionActive();
            }
            if (!id.equals(TicketOrderMapper.class.getName() + ".insertDuringCreationWindow")) {
                return invocation.proceed();
            }
            attempt.writerResource = TransactionSynchronizationManager.getResource(jdbc.getDataSource());
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            var connection = ((Executor) invocation.getTarget()).getTransaction().getConnection();
            try (var statement = connection.createStatement(); var row = statement.executeQuery("SELECT CONNECTION_ID()")) {
                row.next(); attempt.writerConnectionId = row.getLong(1);
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { attempt.writerCompletion = status; }
            });
            attempt.race.inserts.await(10, TimeUnit.SECONDS);
            try { return invocation.proceed(); }
            catch (Throwable failure) {
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof SQLException sql && sql.getErrorCode() == 1062) { attempt.duplicateKey = true; }
                }
                throw failure;
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean IsolatedQualificationMySqlDataSource dataSource(DataSourceProperties properties) throws Exception {
            return new IsolatedQualificationMySqlDataSource(properties);
        }
        @Bean CreationRace creationRace(JdbcTemplate jdbc) { return new CreationRace(jdbc); }
    }
}
