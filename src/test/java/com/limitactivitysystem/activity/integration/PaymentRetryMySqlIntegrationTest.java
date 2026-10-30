package com.limitactivitysystem.activity.integration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.observability.PaymentTelemetry;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.ticketorder.exception.PaymentConcurrencyException;
import com.limitactivitysystem.ticketorder.model.TicketOrder;
import com.limitactivitysystem.ticketorder.service.PaymentRetryBackoff;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@AutoConfigureObservability
@Import(PaymentRetryMySqlIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class PaymentRetryMySqlIntegrationTest extends PaymentResourceTestSupport {
    @Autowired private RetryObservation observer;
    @Autowired private io.micrometer.core.instrument.MeterRegistry metrics;
    @MockitoSpyBean private PaymentRetryBackoff backoff;
    private ActivitySession session;
    private SessionZone zone;
    private Logger logger;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach void openPaidScopeAndCaptureRetryEvidence() throws Exception {
        reset(backoff);
        var activity = insertDraft(publisher, "PAID");
        jdbc.update("""
                UPDATE activity SET open_time = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 HOUR),
                    close_time = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 HOUR) WHERE activity_id = ?
                """, activity.getActivityId());
        session = insertSession(activity);
        zone = insertZone(session, "PAID", 10, 10);
        publish(activity, publisher).andExpect(status().isNoContent());
        logger = (Logger) org.slf4j.LoggerFactory.getLogger(PaymentTelemetry.class);
        logs = new ListAppender<>(); logs.start(); logger.addAppender(logs);
    }

    @AfterEach void stopCapture() { logger.detachAppender(logs); logs.stop(); observer.current.remove(); }

    @Test void aRealTimeoutAfterAllResourceWritesRollsBackThenANewAttemptCommits() throws Exception {
        var order = payableOrder();
        var blocker = newOrder(session, buyer);
        var before = snapshot();
        var locked = gate(); var release = gate();
        var holder = async(() -> tx(() -> { orders.findByIdForUpdate(blocker.getOrderId()); locked.countDown(); await(release); return true; }));
        await(locked);
        var run = new Run(blocker.getOrderId());
        double retryBefore = metrics.get("payment.retry").tag("reason", "LOCK_WAIT_TIMEOUT").counter().count();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(run.attempts).hasSize(1);
            assertThat(run.attempts.get(0).completion).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertThat(snapshot()).isEqualTo(before);
            release.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(backoff).pause(anyInt());

        var response = pay(order, run).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"))
                .andReturn().getResponse();
        assertThat(run.attempts).hasSize(2);
        assertThat(run.attempts).extracting(attempt -> attempt.completion)
                .containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK, TransactionSynchronization.STATUS_COMMITTED);
        assertThat(run.attempts).extracting(attempt -> attempt.transactionHolder).doesNotHaveDuplicates();
        assertThat(run.attempts.get(0).ticketsBeforeConflict).isEqualTo(1);
        assertThat(run.attempts.get(0).stockBeforeConflict).isEqualTo(9);
        assertSqlError(run.attempts.get(0).failure, 1205, "40001");
        assertThat(metrics.get("payment.retry").tag("reason", "LOCK_WAIT_TIMEOUT").counter().count()).isEqualTo(retryBefore + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Integer.class)).isEqualTo(1);
        assertThat(purchases.findValidCount(buyer.getId(), session.getActivitySessionId())).isEqualTo(1);
        assertThat(zoneMapper.findById(zone.getZoneId()).getRemainsAmount()).isEqualTo(9);
        PaymentInvariantAssertions.assertConsistent(jdbc);
        assertThat(retryLogs()).singleElement().asString().contains("retryCount=1", "outcome=SUCCESS");
        evidence("day8-retry-success", Map.of("attempts", attemptEvidence(run), "logs", retryLogs(),
                "httpStatus", response.getStatus(), "response", objectMapper.readTree(response.getContentAsString()),
                "resourceConsistency", "PASS", "rollbackCompletedBeforeBackoff", true));
    }

    @Test void threeRealTimeoutsReturn503AndLeaveEveryResourceAndTheOrderUnchanged() throws Exception {
        var order = payableOrder();
        var blocker = newOrder(session, buyer);
        var before = snapshot();
        var locked = gate(); var release = gate();
        var holder = async(() -> tx(() -> { orders.findByIdForUpdate(blocker.getOrderId()); locked.countDown(); await(release); return true; }));
        await(locked);
        var run = new Run(blocker.getOrderId());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(run.attempts).allSatisfy(attempt -> assertThat(attempt.completion).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(snapshot()).isEqualTo(before);
            return invocation.callRealMethod();
        }).when(backoff).pause(anyInt());

        var response = pay(order, run).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(PaymentConcurrencyException.CODE))
                .andExpect(jsonPath("$.reason").value("LOCK_WAIT_TIMEOUT"))
                .andExpect(jsonPath("$.attempts").value(3)).andReturn().getResponse();
        release.countDown(); assertThat(holder.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(run.attempts).hasSize(3).allSatisfy(attempt -> {
            assertThat(attempt.completion).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertSqlError(attempt.failure, 1205, "40001");
            assertThat(attempt.ticketsBeforeConflict).isEqualTo(1);
        });
        assertThat(run.attempts).extracting(attempt -> attempt.transactionHolder).doesNotHaveDuplicates();
        assertThat(snapshot()).isEqualTo(before);
        assertThat(orders.findById(order.getOrderId()).getStatus()).isEqualTo("PENDING_PAYMENT");
        PaymentInvariantAssertions.assertConsistent(jdbc);
        assertThat(retryLogs()).singleElement().asString().contains("retryCount=2", "outcome=FAILED");
        evidence("day8-retry-exhausted", Map.of("attempts", attemptEvidence(run), "logs", retryLogs(),
                "httpStatus", response.getStatus(), "response", objectMapper.readTree(response.getContentAsString()),
                "allResourcesAndOrdersUnchanged", true));
    }

    @Test void reversedOrderLocksCauseARealDeadlockAndAscendingLocksBothCommit() throws Exception {
        var low = newOrder(session, buyer).getOrderId();
        var high = newOrder(session, buyer).getOrderId();
        var before = snapshot();
        var bothLocked = new CyclicBarrier(2);
        var a = new LockTrace("A", low, high);
        var b = new LockTrace("B", high, low);
        var first = async(() -> lockPair(a, () -> barrier(bothLocked)));
        var second = async(() -> lockPair(b, () -> barrier(bothLocked)));
        first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        assertThat(List.of(a.completion, b.completion)).containsExactlyInAnyOrder(
                TransactionSynchronization.STATUS_COMMITTED, TransactionSynchronization.STATUS_ROLLED_BACK);
        var victim = a.failure != null ? a : b;
        assertSqlError(victim.failure, 1213, "40001");
        String innodb = jdbc.queryForList("SHOW ENGINE INNODB STATUS").get(0).get("Status").toString();
        int deadlockStart = innodb.indexOf("LATEST DETECTED DEADLOCK");
        int deadlockEnd = innodb.indexOf("TRANSACTIONS", deadlockStart);
        assertThat(deadlockStart).isGreaterThanOrEqualTo(0);
        String deadlock = innodb.substring(deadlockStart, deadlockEnd);
        assertThat(deadlock).contains(jdbc.queryForObject("SELECT DATABASE()", String.class), "ticket_order", "ROLL BACK TRANSACTION");
        Path report = Path.of("target", "verification", "day8-deadlock-innodb.txt");
        Files.createDirectories(report.getParent()); Files.writeString(report, deadlock);
        evidence("day8-deadlock", Map.of("transactions", List.of(traceEvidence(a), traceEvidence(b)),
                "mysqlErrorCode", 1213, "sqlState", "40001", "innodbReport", report.toString()));

        var smallerLocked = gate(); var release = gate(); var waiting = gate();
        var orderedA = new LockTrace("A", low, high);
        var orderedB = new LockTrace("B", low, high);
        var orderedFirst = async(() -> lockPair(orderedA, () -> { smallerLocked.countDown(); await(release); }));
        await(smallerLocked);
        var orderedSecond = async(() -> { waiting.countDown(); return lockPair(orderedB, () -> { }); });
        await(waiting);
        // The waiter publishes its connection ID immediately before requesting the first row lock.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (orderedB.connectionId.get() == 0) { assertThat(System.nanoTime()).isLessThan(deadline); Thread.onSpinWait(); }
        var wait = awaitDatabaseLockWait(orderedB.connectionId.get(), orderedA.connectionId.get(), "ticket_order");
        release.countDown(); orderedFirst.get(10, TimeUnit.SECONDS); orderedSecond.get(10, TimeUnit.SECONDS);
        assertThat(orderedA.failure).isNull(); assertThat(orderedB.failure).isNull();
        assertThat(orderedA.completion).isEqualTo(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(orderedB.completion).isEqualTo(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(snapshot()).isEqualTo(before);
        evidence("day8-ordered-locks", Map.of("transactions", List.of(traceEvidence(orderedA), traceEvidence(orderedB)),
                "observedLockWait", wait, "bothCommitted", true, "resourcesUnchanged", true));
    }

    private boolean lockPair(LockTrace trace, Runnable afterFirstLock) {
        try {
            tx(() -> {
                trace.connectionId.set(connectionId());
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) { trace.completion = status; }
                });
                trace.sql.add("BEGIN ISOLATION LEVEL READ COMMITTED");
                trace.sql.add("SELECT * FROM ticket_order WHERE order_id = " + trace.first + " FOR UPDATE");
                orders.findByIdForUpdate(trace.first);
                trace.sql.add("ACQUIRED " + trace.first);
                afterFirstLock.run();
                trace.sql.add("SELECT * FROM ticket_order WHERE order_id = " + trace.second + " FOR UPDATE");
                orders.findByIdForUpdate(trace.second);
                trace.sql.add("ACQUIRED " + trace.second);
                return true;
            });
            trace.sql.add("COMMIT"); return true;
        } catch (RuntimeException failure) {
            trace.failure = failure; trace.sql.add("ROLLBACK"); return false;
        }
    }

    private void barrier(CyclicBarrier barrier) {
        try { barrier.await(10, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private TicketOrder payableOrder() throws Exception {
        var seat = seatMapper.findByZoneId(zone.getZoneId()).get(0);
        var response = postJson("/ticket-orders", buyer, Map.of("requestId", UUID.randomUUID().toString(),
                "sessionId", session.getActivitySessionId(), "items", List.of(Map.of("zoneId", zone.getZoneId(),
                        "allocationMode", "SELECT_SEAT", "requestedSeatId", seat.getSeatId(), "quantity", 1,
                        "acceptNonAdjacent", false)))).andExpect(status().isCreated()).andReturn().getResponse();
        return orders.findById(objectMapper.readTree(response.getContentAsString()).get("orderId").asLong());
    }

    private org.springframework.test.web.servlet.ResultActions pay(TicketOrder order, Run run) throws Exception {
        observer.current.set(run);
        try {
            return mvc.perform(post("/ticket-orders/{id}/pay", order.getOrderId()).header(HttpHeaders.AUTHORIZATION, bearer(buyer)));
        } finally { observer.current.remove(); }
    }

    private List<List<Map<String, Object>>> snapshot() {
        return List.of("ticket_order", "ticket_order_item", "session_zone", "session_seat", "user_session_purchase", "ticket")
                .stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1")).toList();
    }

    private List<String> retryLogs() {
        var captured = logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(line -> line.startsWith("Ticket payment retry ")).toList();
        assertThat(captured).singleElement().asString().contains("retryCount=", "durationMs=", "reason=LOCK_WAIT_TIMEOUT", "outcome=");
        return captured;
    }

    private List<Map<String, Object>> attemptEvidence(Run run) {
        return run.attempts.stream().map(attempt -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("connectionId", attempt.connectionId); result.put("transactionHolder", attempt.transactionHolder);
            result.put("completion", attempt.completion == TransactionSynchronization.STATUS_COMMITTED ? "COMMIT" : "ROLLBACK");
            result.put("sql", attempt.sql); result.put("failure", failureEvidence(attempt.failure));
            result.put("uncommittedTickets", attempt.ticketsBeforeConflict); result.put("uncommittedStock", attempt.stockBeforeConflict);
            return result;
        }).toList();
    }

    private Map<String, Object> traceEvidence(LockTrace trace) {
        return Map.of("transaction", trace.name, "connectionId", trace.connectionId.get(), "lockOrder", List.of(trace.first, trace.second),
                "sqlSequence", trace.sql, "completion", trace.completion == TransactionSynchronization.STATUS_COMMITTED ? "COMMIT" : "ROLLBACK",
                "failure", failureEvidence(trace.failure));
    }

    private Map<String, Object> failureEvidence(Throwable failure) {
        if (failure == null) { return Map.of(); }
        SQLException sql = sqlCause(failure);
        StringWriter stack = new StringWriter(); failure.printStackTrace(new PrintWriter(stack));
        return Map.of("exceptionClass", failure.getClass().getName(), "stackTrace", stack.toString(),
                "mysqlErrorCode", sql.getErrorCode(), "sqlState", sql.getSQLState(), "mysqlMessage", sql.getMessage());
    }

    private static SQLException sqlCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) { return sql; }
        }
        throw new AssertionError("Expected an actual JDBC error", failure);
    }

    private void assertSqlError(Throwable failure, int code, String state) {
        assertThat(failure).isNotNull();
        var sql = sqlCause(failure);
        assertThat(sql.getErrorCode()).isEqualTo(code); assertThat(sql.getSQLState()).isEqualTo(state);
    }

    static class LockTrace {
        final String name; final Long first; final Long second;
        final AtomicLong connectionId = new AtomicLong();
        final List<String> sql = new CopyOnWriteArrayList<>();
        volatile int completion = -1;
        volatile RuntimeException failure;
        LockTrace(String name, Long first, Long second) { this.name = name; this.first = first; this.second = second; }
    }

    static class Run {
        final Long blocker;
        final List<Attempt> attempts = new ArrayList<>();
        Run(Long blocker) { this.blocker = blocker; }
    }

    static class Attempt {
        long connectionId;
        int transactionHolder;
        int completion = -1;
        int ticketsBeforeConflict;
        int stockBeforeConflict;
        RuntimeException failure;
        final List<String> sql = new ArrayList<>();
    }

    @Intercepts({@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
            @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})})
    static class RetryObservation implements Interceptor {
        final ThreadLocal<Run> current = new ThreadLocal<>();
        private final JdbcTemplate jdbc;
        private final DataSource dataSource;
        RetryObservation(JdbcTemplate jdbc, DataSource dataSource) { this.jdbc = jdbc; this.dataSource = dataSource; }

        @Override public Object intercept(Invocation invocation) throws Throwable {
            Run run = current.get();
            if (run == null) { return invocation.proceed(); }
            var statement = (MappedStatement) invocation.getArgs()[0];
            if (statement.getId().endsWith("TicketOrderMapper.findByIdForUpdate")) {
                var attempt = new Attempt(); run.attempts.add(attempt);
                attempt.connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                attempt.transactionHolder = System.identityHashCode(TransactionSynchronizationManager.getResource(dataSource));
                jdbc.execute((ConnectionCallback<Void>) connection -> {
                    int previous = jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
                    try (var sql = connection.createStatement()) { sql.execute("SET SESSION innodb_lock_wait_timeout = 1"); }
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void afterCompletion(int status) {
                            attempt.completion = status;
                            try (var sql = connection.createStatement()) { sql.execute("SET SESSION innodb_lock_wait_timeout = " + previous); }
                            catch (SQLException failure) { throw new IllegalStateException("Could not restore test timeout", failure); }
                        }
                    });
                    return null;
                });
            }
            if (run.attempts.isEmpty()) { return invocation.proceed(); }
            var attempt = run.attempts.get(run.attempts.size() - 1);
            attempt.sql.add(statement.getBoundSql(invocation.getArgs()[1]).getSql().replaceAll("\\s+", " ").trim());
            Object result = invocation.proceed();
            if (statement.getId().endsWith("TicketMapper.insert")) {
                attempt.ticketsBeforeConflict = jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Integer.class);
                attempt.stockBeforeConflict = jdbc.queryForObject("SELECT SUM(remains_amount) FROM session_zone", Integer.class);
                attempt.sql.add("SELECT order_id FROM ticket_order WHERE order_id = " + run.blocker + " FOR UPDATE");
                try {
                    jdbc.queryForObject("SELECT order_id FROM ticket_order WHERE order_id = ? FOR UPDATE", Long.class, run.blocker);
                } catch (RuntimeException failure) { attempt.failure = failure; throw failure; }
            }
            return result;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean IsolatedQualificationMySqlDataSource dataSource(DataSourceProperties properties) throws Exception {
            return new IsolatedQualificationMySqlDataSource(properties);
        }
        @Bean RetryObservation retryObservation(JdbcTemplate jdbc, DataSource dataSource) { return new RetryObservation(jdbc, dataSource); }
    }
}
