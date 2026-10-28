package com.limitactivitysystem.activity.integration;

import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.whitelist.exception.WhitelistErrorCode;
import com.limitactivitysystem.whitelist.exception.WhitelistException;
import com.limitactivitysystem.whitelist.exception.QualificationDeniedException;
import com.limitactivitysystem.whitelist.model.WhitelistScopeType;
import com.limitactivitysystem.whitelist.service.PublisherWhitelistService;
import com.limitactivitysystem.whitelist.service.QualificationService;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import(WhitelistQualificationConcurrencyMySqlIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class WhitelistQualificationConcurrencyMySqlIntegrationTest extends ActivityMySqlTestSupport {
    @Autowired private QualificationService qualification;
    @Autowired private PublisherWhitelistService management;
    @Autowired private RootReads observer;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final List<CountDownLatch> gates = new ArrayList<>();
    private Activity activity;
    private ActivitySession session;

    @BeforeEach
    void prepareQualification() throws Exception {
        activity = insertDraft(publisher, "FREE");
        session = insertSession(activity);
        asPublisher(() -> management.enable(WhitelistScopeType.ACTIVITY, activity.getActivityId()));
        asPublisher(() -> management.addMember(WhitelistScopeType.ACTIVITY, activity.getActivityId(), buyer.getId()));
    }

    @AfterEach
    void finishWorkersAndRemoveOnlyIsolatedWhitelistData() throws Exception {
        gates.forEach(CountDownLatch::countDown);
        workers.shutdownNow();
        assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        observer.current.remove();
        // This datasource owns a random schema; no production whitelist table is touched.
        jdbc.update("DELETE FROM whitelist_member");
        jdbc.update("DELETE FROM whitelist_config");
    }

    @Test
    void qualificationFirstKeepsTheExistingDecisionUntilTheBusinessTransactionEnds() throws Exception {
        CountDownLatch checked = gate();
        CountDownLatch commit = gate();
        Probe reader = new Probe();
        Future<Boolean> action = start(reader, () -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
            boolean allowed = allowed();
            checked.countDown();
            await(commit);
            return allowed;
        }));
        await(checked);
        Probe writer = new Probe();
        Future<?> change = start(writer, () -> removeMember());
        assertBlocked(writer, reader);
        commit.countDown();
        assertThat(action.get(10, TimeUnit.SECONDS)).isTrue();
        change.get(10, TimeUnit.SECONDS);
        assertThat(allowed()).isFalse();
    }

    @Test
    void committedListRemovalIsSeenByTheWaitingQualification() throws Exception {
        CountDownLatch removed = gate();
        CountDownLatch commit = gate();
        Probe writer = new Probe();
        Future<?> change = start(writer, () -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
            removeMember();
            removed.countDown();
            await(commit);
            return null;
        }));
        await(removed);
        Probe reader = new Probe();
        Future<Boolean> action = start(reader, this::allowed);
        assertBlocked(reader, writer);
        commit.countDown();
        change.get(10, TimeUnit.SECONDS);
        assertThat(action.get(10, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void qualificationsShareTheRootInsteadOfSerializingConsumers() throws Exception {
        CountDownLatch bothChecked = new CountDownLatch(2);
        CountDownLatch commit = gate();
        Probe first = new Probe();
        Probe second = new Probe();
        Callable<Boolean> action = () -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
            boolean result = allowed();
            bothChecked.countDown();
            await(commit);
            return result;
        });
        Future<Boolean> a = start(first, action);
        Future<Boolean> b = start(second, action);
        await(bothChecked);
        assertThat(first.connectionId.get()).isPositive().isNotEqualTo(second.connectionId.get());
        commit.countDown();
        assertThat(a.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(b.get(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void anUnconfiguredScopeIsProtectedAgainstConcurrentCreationOfAnEmptySessionList() throws Exception {
        asPublisher(() -> { management.disable(WhitelistScopeType.ACTIVITY, activity.getActivityId()); return null; });
        CountDownLatch checked = gate();
        CountDownLatch commit = gate();
        Probe reader = new Probe();
        Future<Boolean> action = start(reader, () -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
            boolean allowed = allowed();
            checked.countDown();
            await(commit);
            return allowed;
        }));
        await(checked);
        Probe writer = new Probe();
        Future<?> change = start(writer, () -> asPublisher(() ->
                management.enable(WhitelistScopeType.SESSION, session.getActivitySessionId())));
        assertBlocked(writer, reader);
        commit.countDown();
        assertThat(action.get(10, TimeUnit.SECONDS)).isTrue();
        change.get(10, TimeUnit.SECONDS);
        assertThat(allowed()).isFalse();
    }

    @Test
    void anOlderRepeatableReadSnapshotDoesNotReuseOrderCreationEligibility() throws Exception {
        CountDownLatch snapshot = gate();
        CountDownLatch modified = gate();
        Future<Boolean> action = start(new Probe(), () -> transaction(TransactionDefinition.ISOLATION_REPEATABLE_READ, () -> {
            assertThat(snapshotMemberCount()).isEqualTo(1);
            snapshot.countDown();
            await(modified);
            boolean current = allowed();
            assertThat(snapshotMemberCount()).isEqualTo(1); // The old ordinary-read snapshot still exists.
            return current;
        }));
        await(snapshot);
        removeMember();
        modified.countDown();
        assertThat(action.get(10, TimeUnit.SECONDS)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(WhitelistScopeType.class)
    void cancellationCommitsFirstAndWaitingWhitelistMutationRejectsTheCurrentLifecycle(WhitelistScopeType scope)
            throws Exception {
        var configsBefore = jdbc.queryForList("SELECT * FROM whitelist_config ORDER BY config_id");
        var membersBefore = jdbc.queryForList("SELECT * FROM whitelist_member ORDER BY member_id");
        CountDownLatch cancelled = gate();
        CountDownLatch commit = gate();
        Probe lifecycle = new Probe();
        Future<?> cancellation = start(lifecycle, () -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
            assertThat(activityMapper.findByIdForUpdate(activity.getActivityId())).isNotNull();
            assertThat(jdbc.update("UPDATE activity SET status = 'CANCELLED' WHERE activity_id = ?",
                    activity.getActivityId())).isEqualTo(1);
            cancelled.countDown();
            await(commit);
            return null;
        }));
        await(cancelled);
        Probe writer = new Probe();
        Future<?> change = start(writer, () -> asPublisher(() -> management.enable(scope, scopeId(scope))));
        assertBlocked(writer, lifecycle);

        commit.countDown();
        cancellation.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> change.get(10, TimeUnit.SECONDS))
                .isInstanceOfSatisfying(ExecutionException.class, exception ->
                        assertThat(exception.getCause()).isInstanceOfSatisfying(WhitelistException.class, rejected ->
                                assertThat(rejected.getErrorCode()).isEqualTo(WhitelistErrorCode.WHITELIST_NOT_EDITABLE)));
        assertThat(activityMapper.findById(activity.getActivityId()).getStatus()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForList("SELECT * FROM whitelist_config ORDER BY config_id")).isEqualTo(configsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM whitelist_member ORDER BY member_id")).isEqualTo(membersBefore);
    }

    @ParameterizedTest
    @EnumSource(WhitelistScopeType.class)
    void whitelistMutationKeepsTheRootLockUntilItsOuterTransactionCommitsBeforeCancellation(WhitelistScopeType scope)
            throws Exception {
        CountDownLatch changed = gate();
        CountDownLatch commit = gate();
        Probe writer = new Probe();
        Future<?> change = start(writer, () -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED,
                () -> asPublisher(() -> {
                    management.enable(scope, scopeId(scope));
                    management.addMember(scope, scopeId(scope), otherPublisher.getId());
                    changed.countDown();
                    await(commit);
                    return null;
                })));
        await(changed);
        Probe lifecycle = new Probe();
        Future<?> cancellation = start(lifecycle, () -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
            assertThat(activityMapper.findByIdForUpdate(activity.getActivityId())).isNotNull();
            assertThat(jdbc.update("UPDATE activity SET status = 'CANCELLED' WHERE activity_id = ?",
                    activity.getActivityId())).isEqualTo(1);
            return null;
        }));
        assertBlocked(lifecycle, writer);

        commit.countDown();
        change.get(10, TimeUnit.SECONDS);
        cancellation.get(10, TimeUnit.SECONDS);
        assertThat(activityMapper.findById(activity.getActivityId()).getStatus()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM whitelist_member m
                JOIN whitelist_config c ON c.config_id = m.config_id
                WHERE c.scope_type = ? AND c.scope_id = ? AND m.user_id = ?
                """, Long.class, scope.name(), scopeId(scope), otherPublisher.getId())).isEqualTo(1L);
        assertThatThrownBy(() -> asPublisher(() -> management.enable(scope, scopeId(scope))))
                .isInstanceOfSatisfying(WhitelistException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(WhitelistErrorCode.WHITELIST_NOT_EDITABLE));
    }

    private Long scopeId(WhitelistScopeType scope) {
        return scope == WhitelistScopeType.ACTIVITY ? activity.getActivityId() : session.getActivitySessionId();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void businessEntriesKeepQualificationLocksUntilTheOuterActionCommits(boolean recheck) throws Exception {
        asPublisher(() -> management.addMember(WhitelistScopeType.ACTIVITY, activity.getActivityId(), publisher.getId()));
        CountDownLatch checked = gate();
        CountDownLatch commit = gate();
        Probe reader = new Probe();
        Future<Long> action = start(reader, () -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED,
                () -> asPublisher(() -> {
                    Long userId = recheck ? qualification.checkAgain(activity.getActivityId(), session.getActivitySessionId())
                            : qualification.check(activity.getActivityId(), session.getActivitySessionId());
                    checked.countDown();
                    await(commit);
                    return userId;
                })));
        await(checked);
        Probe writer = new Probe();
        Future<?> change = start(writer, () -> asPublisher(() -> {
            management.removeMember(WhitelistScopeType.ACTIVITY, activity.getActivityId(), publisher.getId());
            return null;
        }));
        assertBlocked(writer, reader);
        commit.countDown();
        assertThat(action.get(10, TimeUnit.SECONDS)).isEqualTo(publisher.getId());
        change.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> asPublisher(() -> new TransactionTemplate(transactionManager).execute(status ->
                qualification.checkAgain(activity.getActivityId(), session.getActivitySessionId()))))
                .isInstanceOf(QualificationDeniedException.class);
    }

    private long snapshotMemberCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM whitelist_member WHERE user_id = ?", Long.class, buyer.getId());
    }

    private Void removeMember() throws Exception {
        return asPublisher(() -> {
            management.removeMember(WhitelistScopeType.ACTIVITY, activity.getActivityId(), buyer.getId());
            return null;
        });
    }

    private boolean allowed() {
        return qualification.canParticipate(buyer.getId(), activity.getActivityId(), session.getActivitySessionId());
    }

    private <T> T transaction(int isolation, Callable<T> action) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(isolation);
        return transaction.execute(status -> {
            try { return action.call(); } catch (Exception exception) { throw new IllegalStateException(exception); }
        });
    }

    private <T> Future<T> start(Probe probe, Callable<T> action) {
        return workers.submit(() -> {
            observer.current.set(probe);
            try { return action.call(); }
            finally { observer.current.remove(); SecurityContextHolder.clearContext(); }
        });
    }

    private CountDownLatch gate() {
        CountDownLatch gate = new CountDownLatch(1);
        gates.add(gate);
        return gate;
    }

    private void assertBlocked(Probe waiter, Probe holder) throws InterruptedException {
        await(waiter.attempted);
        assertThat(waiter.connectionId.get()).isPositive().isNotEqualTo(holder.connectionId.get());
        assertThat(waiter.acquired.await(250, TimeUnit.MILLISECONDS)).isFalse();
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("worker reached the expected transaction step").isTrue();
    }

    private static class Probe {
        final CountDownLatch attempted = new CountDownLatch(1);
        final CountDownLatch acquired = new CountDownLatch(1);
        final AtomicLong connectionId = new AtomicLong();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        IsolatedQualificationMySqlDataSource dataSource(DataSourceProperties properties) throws Exception {
            return new IsolatedQualificationMySqlDataSource(properties);
        }

        @Bean RootReads rootReads() { return new RootReads(); }
    }

    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static class RootReads implements Interceptor {
        final ThreadLocal<Probe> current = new ThreadLocal<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Probe probe = current.get();
            String id = ((MappedStatement) invocation.getArgs()[0]).getId();
            boolean root = id.equals("com.limitactivitysystem.activity.mapper.ActivityMapper.findByIdForShare")
                    || id.equals("com.limitactivitysystem.activity.mapper.ActivityMapper.findByIdForUpdate");
            if (probe != null && root) {
                var connection = ((Executor) invocation.getTarget()).getTransaction().getConnection();
                try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
                    result.next();
                    probe.connectionId.set(result.getLong(1));
                }
                probe.attempted.countDown();
            }
            Object result = invocation.proceed();
            if (probe != null && root) { probe.acquired.countDown(); }
            return result;
        }
    }
}
