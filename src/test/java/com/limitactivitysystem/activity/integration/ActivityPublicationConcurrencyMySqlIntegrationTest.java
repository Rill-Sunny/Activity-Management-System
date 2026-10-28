package com.limitactivitysystem.activity.integration;

import com.limitactivitysystem.activity.exception.ActivityErrorCode;
import com.limitactivitysystem.activity.exception.ActivityException;
import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.activity.service.PublisherActivityPublishService;
import com.limitactivitysystem.activity.service.PublisherZoneSeatService;
import com.limitactivitysystem.security.JwtTestTokens;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import(ActivityPublicationConcurrencyMySqlIntegrationTest.LockObservationConfiguration.class)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class ActivityPublicationConcurrencyMySqlIntegrationTest extends ActivityMySqlTestSupport {

    @Autowired private PublisherActivityPublishService publishService;
    @Autowired private PublisherZoneSeatService seatService;
    @Autowired private RootLockObserver lockObserver;

    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final List<CountDownLatch> commitGates = new ArrayList<>();

    @AfterEach
    void finishWorkersBeforeDeletingFixtures() throws InterruptedException {
        commitGates.forEach(CountDownLatch::countDown);
        workers.shutdownNow();
        assertThat(workers.awaitTermination(15, TimeUnit.SECONDS)).as("workers released all database locks").isTrue();
    }

    @Test
    void seatConfigurationCommitsFirstAndWaitingPublicationSeesTheNewSeat() throws Exception {
        Activity activity = insertDraft(publisher, "PAID");
        SessionZone zone = insertZone(insertSession(activity), "PAID", 10, 9);
        CountDownLatch configured = new CountDownLatch(1);
        CountDownLatch allowSeatCommit = commitGate();
        RootLockProbe seats = new RootLockProbe(false);
        RootLockProbe publication = new RootLockProbe(true);

        Future<Outcome> seatResult = submit(seats, () -> {
            holdTransaction(TransactionDefinition.ISOLATION_REPEATABLE_READ, () -> {
                seatService.createSeats(zone.getZoneId(), oneSeatRequest(10));
                configured.countDown();
                await(allowSeatCommit, "allow seat transaction to commit");
            });
            return null;
        });
        try {
            await(configured, "the tenth seat was inserted but not committed");
            Future<Outcome> publishResult = submit(publication, () -> {
                publishService.publishActivity(activity.getActivityId());
                return null;
            });
            assertWaitingOnAnotherConnection(publication, seats);
            allowSeatCommit.countDown();
            assertThat(seatResult.get(15, TimeUnit.SECONDS).errorCode()).isNull();
            assertThat(publishResult.get(15, TimeUnit.SECONDS).errorCode()).isNull();
            assertThat(activityMapper.findById(activity.getActivityId()).getStatus()).isEqualTo("PUBLISHED");
            assertThat(seatMapper.countByZoneId(zone.getZoneId())).isEqualTo(10);
            assertThat(zoneMapper.findById(zone.getZoneId()).getTotalAmount()).isEqualTo(10);
        } finally {
            allowSeatCommit.countDown();
        }
    }

    @Test
    void publicationCommitsFirstAndWaitingSeatConfigurationRechecksPublishedStatus() throws Exception {
        Activity activity = insertDraft(publisher, "PAID");
        SessionZone zone = insertZone(insertSession(activity), "PAID", 10, 10);
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch allowPublishCommit = commitGate();
        RootLockProbe publication = new RootLockProbe(true);
        RootLockProbe seats = new RootLockProbe(false);

        Future<Outcome> publishResult = submit(publication, () -> {
            holdTransaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
                publishService.publishActivity(activity.getActivityId());
                published.countDown();
                await(allowPublishCommit, "allow publication transaction to commit");
            });
            return null;
        });
        try {
            await(published, "PUBLISHED was written but not committed");
            Future<Outcome> seatResult = submit(seats, () -> {
                seatService.createSeats(zone.getZoneId(), oneSeatRequest(11));
                return null;
            });
            assertWaitingOnAnotherConnection(seats, publication);
            allowPublishCommit.countDown();
            assertThat(publishResult.get(15, TimeUnit.SECONDS).errorCode()).isNull();
            assertThat(seatResult.get(15, TimeUnit.SECONDS).errorCode()).isEqualTo(ActivityErrorCode.ACTIVITY_NOT_DRAFT);
            assertThat(activityMapper.findById(activity.getActivityId()).getStatus()).isEqualTo("PUBLISHED");
            assertThat(seatMapper.countByZoneId(zone.getZoneId())).isEqualTo(10);
        } finally {
            allowPublishCommit.countDown();
        }
    }

    @Test
    void simultaneousStartNeverPublishesAConfigurationWithTheWrongSeatCount() throws Exception {
        Activity activity = insertDraft(publisher, "PAID");
        SessionZone zone = insertZone(insertSession(activity), "PAID", 10, 9);
        CyclicBarrier start = new CyclicBarrier(2);
        Future<Outcome> publication = submit(new RootLockProbe(true), () -> {
            start.await(10, TimeUnit.SECONDS);
            publishService.publishActivity(activity.getActivityId());
            return null;
        });
        Future<Outcome> seats = submit(new RootLockProbe(false), () -> {
            start.await(10, TimeUnit.SECONDS);
            seatService.createSeats(zone.getZoneId(), oneSeatRequest(10));
            return null;
        });
        Outcome publishOutcome = publication.get(15, TimeUnit.SECONDS);
        assertThat(seats.get(15, TimeUnit.SECONDS).errorCode()).isNull();
        assertThat(seatMapper.countByZoneId(zone.getZoneId())).isEqualTo(10);
        if (publishOutcome.errorCode() == null) {
            assertThat(activityMapper.findById(activity.getActivityId()).getStatus()).isEqualTo("PUBLISHED");
        } else {
            // With only nine initial seats, publication may legitimately fail before configuration finishes.
            assertThat(publishOutcome.errorCode()).isEqualTo(ActivityErrorCode.SEAT_COUNT_MISMATCH);
            assertThat(activityMapper.findById(activity.getActivityId()).getStatus()).isEqualTo("DRAFT");
        }
    }

    @Test
    void failureAfterTheRealStatusUpdateRollsBackPublicationAndAllowsRetry() throws Exception {
        Activity activity = insertDraft(publisher, "PAID");
        SessionZone zone = insertZone(insertSession(activity), "PAID", 10, 10);
        assertThatThrownBy(() -> asPublisher(() -> lockObserver.failAfterNextStatusUpdate(() -> {
            publishService.publishActivity(activity.getActivityId());
            return null;
        }))).hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Test failure after publication status UPDATE");
        assertThat(activityMapper.findById(activity.getActivityId()).getStatus()).isEqualTo("DRAFT");
        assertThat(seatMapper.countByZoneId(zone.getZoneId())).isEqualTo(10);
        asPublisher(() -> {
            publishService.publishActivity(activity.getActivityId());
            return null;
        });
        assertThat(activityMapper.findById(activity.getActivityId()).getStatus()).isEqualTo("PUBLISHED");
    }

    private Future<Outcome> submit(RootLockProbe probe, Callable<?> operation) {
        return workers.submit(() -> asPublisher(() -> lockObserver.observe(probe, () -> {
            try {
                operation.call();
                return new Outcome(null);
            } catch (ActivityException exception) {
                // Catch after the proxied Service/TransactionTemplate has already rolled back.
                return new Outcome(exception.getErrorCode());
            }
        })));
    }

    private void holdTransaction(int isolation, Runnable operation) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(isolation);
        transaction.setTimeout(10);
        transaction.executeWithoutResult(status -> operation.run());
    }

    private void assertWaitingOnAnotherConnection(RootLockProbe waiter, RootLockProbe holder) throws InterruptedException {
        await(waiter.attempted, "the second transaction reached the real root locking query");
        assertThat(waiter.connectionId.get()).isNotNull().isNotEqualTo(holder.connectionId.get());
        assertThat(waiter.acquired.await(250, TimeUnit.MILLISECONDS))
                .as("the second locking query waits for the first transaction's commit").isFalse();
    }

    private CountDownLatch commitGate() {
        CountDownLatch gate = new CountDownLatch(1);
        commitGates.add(gate);
        return gate;
    }

    private static void await(CountDownLatch latch, String description) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).as(description).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(description, exception);
        }
    }

    private record Outcome(ActivityErrorCode errorCode) {
    }

    private static class RootLockProbe {
        private final boolean publishing;
        private final CountDownLatch attempted = new CountDownLatch(1);
        private final CountDownLatch acquired = new CountDownLatch(1);
        private final AtomicReference<Long> connectionId = new AtomicReference<>();

        private RootLockProbe(boolean publishing) {
            this.publishing = publishing;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LockObservationConfiguration {
        @Bean
        RootLockObserver rootLockObserver(JdbcTemplate jdbc) {
            return new RootLockObserver(jdbc);
        }
    }

    // Observe the real MyBatis query on its Spring-managed connection; MySQL provides the row lock.
    @Intercepts({@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})})
    static class RootLockObserver implements Interceptor {
        private final JdbcTemplate jdbc;
        private final ThreadLocal<RootLockProbe> current = new ThreadLocal<>();
        private final ThreadLocal<Boolean> failStatusUpdate = new ThreadLocal<>();

        RootLockObserver(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        <T> T observe(RootLockProbe probe, Callable<T> operation) throws Exception {
            current.set(probe);
            try {
                return operation.call();
            } finally {
                current.remove();
            }
        }

        <T> T failAfterNextStatusUpdate(Callable<T> operation) throws Exception {
            failStatusUpdate.set(true);
            try {
                return operation.call();
            } finally {
                failStatusUpdate.remove();
            }
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            RootLockProbe probe = current.get();
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            if (Boolean.TRUE.equals(failStatusUpdate.get())
                    && statement.getId().equals(ActivityMapper.class.getName() + ".updateStatus")) {
                invocation.proceed();
                failStatusUpdate.remove();
                throw new IllegalStateException("Test failure after publication status UPDATE");
            }
            if (probe == null || !statement.getId().equals(ActivityMapper.class.getName() + ".findByIdForUpdate")) {
                return invocation.proceed();
            }
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if (probe.publishing) {
                assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            }
            probe.connectionId.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            probe.attempted.countDown();
            Object result = invocation.proceed();
            probe.acquired.countDown();
            return result;
        }
    }
}
