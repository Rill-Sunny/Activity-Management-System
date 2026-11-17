package com.limitactivitysystem.activity.integration;

import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.security.JwtTestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Schema and transactional SQL contracts; HTTP authorization is covered by FreeReservationMySqlIntegrationTest. */
@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import(FreeReservationSchemaMySqlIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class FreeReservationSchemaMySqlIntegrationTest extends PaymentResourceTestSupport {
    // Keep the historical schema target; cache tables were introduced in V13.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.limitactivitysystem.cache.CacheInvalidationRecorder cacheInvalidations;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.limitactivitysystem.cache.CacheInvalidationDelivery cacheDelivery;
    private ActivitySession session;
    private ActivitySession otherSession;

    @BeforeEach void createFreePublishedSessionsWithoutTicketResources() throws Exception {
        var activity = insertDraft(publisher, "FREE");
        session = insertSession(activity);
        otherSession = insertSession(activity);
        publish(activity, publisher).andExpect(status().isNoContent());
    }

    @AfterEach void removeOnlyTheOwnedReservationFixtures() {
        jdbc.update("DELETE FROM free_reservation");
        jdbc.update("DELETE FROM free_session_quota");
    }

    @Test void migrationDefinesThirteenTablesAndTheGeneratedCurrentKey() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'", Integer.class)).isEqualTo(13);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = DATABASE() AND constraint_type = 'FOREIGN KEY'", Integer.class)).isZero();
        var column = jdbc.queryForMap("SELECT extra, generation_expression FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'free_reservation' AND column_name = 'active_session_id'");
        assertThat(column.get("EXTRA").toString()).contains("VIRTUAL GENERATED");
        assertThat(column.get("GENERATION_EXPRESSION").toString()).contains("ACTIVE", "session_id");
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'free_reservation'
                  AND index_name = 'uq_free_reservation_active' AND non_unique = 0 ORDER BY seq_in_index
                """, String.class)).containsExactly("user_id", "active_session_id");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM free_session_quota", Integer.class)).isZero();
    }

    @Test void creationUsesDatabaseTimeAndCurrentKeyAndRejectsASecondActiveRecord() {
        LocalDateTime before = databaseNow();
        long id = insertActive(buyer.getId(), session.getActivitySessionId());
        var row = jdbc.queryForMap("SELECT reservation_id, status, created_at, cancelled_at, active_session_id FROM free_reservation WHERE reservation_id = ?", id);
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("cancelled_at")).isNull();
        assertThat(((Number) row.get("active_session_id")).longValue()).isEqualTo(session.getActivitySessionId());
        assertThat(jdbc.queryForObject("SELECT created_at FROM free_reservation WHERE reservation_id = ?", LocalDateTime.class, id)).isBetween(before, databaseNow());
        assertThatThrownBy(() -> insertActive(buyer.getId(), session.getActivitySessionId()))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_free_reservation_active");
        insertActive(buyer.getId(), otherSession.getActivitySessionId());
        insertActive(otherPublisher.getId(), session.getActivitySessionId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM free_reservation WHERE status = 'ACTIVE'", Integer.class)).isEqualTo(3);
    }

    @Test void repeatedCancelAndRebookRetainsEveryOldRowAndNeverReleasesTheNewReservationsQuota() throws Exception {
        initializeQuota(session.getActivitySessionId());
        List<Long> history = new ArrayList<>();
        long current = reserve(buyer.getId(), session.getActivitySessionId());
        for (int i = 0; i < 3; i++) {
            long old = current;
            var originalCreated = jdbc.queryForObject("SELECT created_at FROM free_reservation WHERE reservation_id = ?", LocalDateTime.class, old);
            assertThat(cancel(old)).isTrue();
            history.add(old);
            current = reserve(buyer.getId(), session.getActivitySessionId());
            assertThat(current).isNotEqualTo(old);
            assertThat(cancel(old)).isFalse();
            assertThat(jdbc.queryForObject("SELECT created_at FROM free_reservation WHERE reservation_id = ?", LocalDateTime.class, old)).isEqualTo(originalCreated);
            assertThat(jdbc.queryForObject("SELECT active_session_id FROM free_reservation WHERE reservation_id = ?", Long.class, old)).isNull();
            assertThat(remaining()).isEqualTo(9);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM free_reservation WHERE status = 'CANCELLED'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM free_reservation WHERE status = 'ACTIVE'", Integer.class)).isEqualTo(1);
        assertConsistent();
        evidence("day9-reservation-history", Map.of("cancelledReservationIds", history, "activeReservationId", current,
                "remaining", remaining(), "rows", jdbc.queryForList("SELECT * FROM free_reservation ORDER BY reservation_id")));
    }

    @ParameterizedTest @ValueSource(strings = {"PENDING_PAYMENT", "PAID", "UNKNOWN"})
    void onlyReservationStatesAreAccepted(String state) {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO free_reservation(user_id, session_id, status) VALUES (?, ?, ?)", buyer.getId(), session.getActivitySessionId(), state))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_free_reservation_status");
    }

    @ParameterizedTest @CsvSource({"ACTIVE,2026-10-28 12:01:00,chk_free_reservation_status_time",
            "CANCELLED,NULL,chk_free_reservation_status_time", "CANCELLED,2026-10-28 11:59:59,chk_free_reservation_cancelled_time"})
    void cancellationTimestampMustMatchStateAndFollowCreation(String state, String cancelledAt, String constraint) {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO free_reservation(user_id, session_id, status, created_at, cancelled_at)
                VALUES (?, ?, ?, '2026-10-28 12:00:00', ?)
                """, buyer.getId(), session.getActivitySessionId(), state, "NULL".equals(cancelledAt) ? null : cancelledAt))
                .isInstanceOf(DataAccessException.class).hasMessageContaining(constraint);
    }

    @ParameterizedTest @CsvSource({"0,1,chk_free_reservation_user_id", "-1,1,chk_free_reservation_user_id",
            "1,0,chk_free_reservation_session_id", "1,-1,chk_free_reservation_session_id"})
    void logicalReferenceIdsMustBePositive(long userId, long sessionId, String constraint) {
        assertThatThrownBy(() -> insertActive(userId, sessionId)).isInstanceOf(DataAccessException.class).hasMessageContaining(constraint);
    }

    @ParameterizedTest @CsvSource({"0,0,chk_free_quota_capacity", "-1,0,chk_free_quota_capacity",
            "1,-1,chk_free_quota_remaining", "1,2,chk_free_quota_remaining"})
    void quotaBoundsAreDatabaseConstraints(int capacity, int remaining, String constraint) {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO free_session_quota(session_id, capacity, remaining) VALUES (?, ?, ?)",
                session.getActivitySessionId(), capacity, remaining)).isInstanceOf(DataAccessException.class).hasMessageContaining(constraint);
    }

    @Test void initializationIsFreePublishedOnlyAndNeverResetsAnExistingCounter() {
        var paidActivity = insertDraft(publisher, "PAID");
        var paidSession = insertSession(paidActivity);
        jdbc.update("UPDATE activity SET status = 'PUBLISHED' WHERE activity_id = ?", paidActivity.getActivityId());
        initializeQuota(paidSession.getActivitySessionId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM free_session_quota", Integer.class)).isZero();
        initializeQuota(session.getActivitySessionId());
        reserve(buyer.getId(), session.getActivitySessionId());
        initializeQuota(session.getActivitySessionId());
        assertThat(remaining()).isEqualTo(9);
        assertConsistent();
    }

    @Test void twoFirstInitializersCreateOneQuotaRow() throws Exception {
        var barrier = new CyclicBarrier(2);
        var first = async(() -> { barrier.await(10, TimeUnit.SECONDS); tx(() -> { initializeQuota(session.getActivitySessionId()); return true; }); return true; });
        var second = async(() -> { barrier.await(10, TimeUnit.SECONDS); tx(() -> { initializeQuota(session.getActivitySessionId()); return true; }); return true; });
        assertThat(first.get(10, TimeUnit.SECONDS)).isTrue(); assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM free_session_quota", Integer.class)).isEqualTo(1);
        assertThat(remaining()).isEqualTo(10);
        assertConsistent();
    }

    @Test void twoAccountsCompetingForTheLastSlotCanCommitOnlyOneReservation() throws Exception {
        jdbc.update("UPDATE activity_session SET capacity = 1 WHERE activity_session_id = ?", session.getActivitySessionId());
        initializeQuota(session.getActivitySessionId());
        var barrier = new CyclicBarrier(2);
        var first = async(() -> { barrier.await(10, TimeUnit.SECONDS); return tryReserve(buyer.getId()); });
        var second = async(() -> { barrier.await(10, TimeUnit.SECONDS); return tryReserve(otherPublisher.getId()); });
        assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        assertThat(remaining()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM free_reservation WHERE status = 'ACTIVE'", Integer.class)).isEqualTo(1);
        assertConsistent();
        assertNoPaidResources();
        evidence("day9-last-free-quota", Map.of("capacity", 1, "remaining", remaining(), "activeCount", 1, "invariants", "PASS"));
    }

    @Test void sameAccountConcurrentInsertsRollbackTheLosingQuotaDeduction() throws Exception {
        initializeQuota(session.getActivitySessionId());
        var barrier = new CyclicBarrier(2);
        var first = async(() -> { barrier.await(10, TimeUnit.SECONDS); return tryReserveSameAccount(); });
        var second = async(() -> { barrier.await(10, TimeUnit.SECONDS); return tryReserveSameAccount(); });
        assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        assertThat(remaining()).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM free_reservation WHERE status = 'ACTIVE'", Integer.class)).isEqualTo(1);
        assertConsistent();
        evidence("day9-active-key-race", Map.of("capacity", 10, "remaining", remaining(), "activeCount", 1,
                "loser", "uq_free_reservation_active; full transaction rollback", "invariants", "PASS"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aFailureAfterQuotaOrHistoryWriteRollsBackTheWholeTransaction(boolean afterInsert) {
        initializeQuota(session.getActivitySessionId());
        assertThatThrownBy(() -> tx(() -> {
            assertThat(takeQuota(session.getActivitySessionId())).isEqualTo(1);
            if (afterInsert) { insertActive(buyer.getId(), session.getActivitySessionId()); }
            throw new IllegalStateException("Injected reservation transaction failure");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("Injected reservation");
        assertThat(remaining()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM free_reservation", Integer.class)).isZero();
        assertConsistent();
    }

    @ParameterizedTest @CsvSource({"USER,INVALID_RESERVATION_SCOPE", "SESSION,INVALID_RESERVATION_SCOPE",
            "ACTIVITY,INVALID_RESERVATION_SCOPE", "PAID_ACTIVITY,INVALID_RESERVATION_SCOPE",
            "QUOTA,ACTIVE_RESERVATION_WITHOUT_QUOTA", "CAPACITY,INVALID_QUOTA_SCOPE_OR_CAPACITY",
            "REMAINING,QUOTA_RESERVATION_MISMATCH"})
    void reconciliationDetectsEachOrphanScopeAndQuotaViolation(String corruption, String expected) throws Exception {
        long id = reserve(buyer.getId(), session.getActivitySessionId());
        assertConsistent();
        var detected = tx(true, () -> {
            switch (corruption) {
                case "USER" -> jdbc.update("UPDATE free_reservation SET user_id = ? WHERE reservation_id = ?", Long.MAX_VALUE, id);
                case "SESSION" -> jdbc.update("UPDATE free_reservation SET session_id = ? WHERE reservation_id = ?", Long.MAX_VALUE, id);
                case "ACTIVITY" -> jdbc.update("UPDATE activity_session SET activity_id = ? WHERE activity_session_id = ?", Long.MAX_VALUE, session.getActivitySessionId());
                case "PAID_ACTIVITY" -> jdbc.update("UPDATE activity SET type = 'PAID' WHERE activity_id = ?", session.getActivityId());
                case "QUOTA" -> jdbc.update("DELETE FROM free_session_quota WHERE session_id = ?", session.getActivitySessionId());
                case "CAPACITY" -> jdbc.update("UPDATE activity_session SET capacity = capacity + 1 WHERE activity_session_id = ?", session.getActivitySessionId());
                case "REMAINING" -> jdbc.update("UPDATE free_session_quota SET remaining = remaining + 1 WHERE session_id = ?", session.getActivitySessionId());
                default -> throw new IllegalArgumentException("Unknown corruption: " + corruption);
            }
            var found = violations();
            assertThat(found).anyMatch(row -> expected.equals(row.get("violation")));
            if (corruption.equals("CAPACITY")) {
                assertThat(found).anyMatch(row -> "QUOTA_RESERVATION_MISMATCH".equals(row.get("violation")));
            }
            return found;
        });
        assertConsistent();
        evidence("day9-invariants-" + corruption.toLowerCase(java.util.Locale.ROOT),
                Map.of("corruption", corruption, "detected", detected, "fixtureRestored", true, "invariants", "PASS"));
    }

    private void initializeQuota(Long sessionId) {
        jdbc.update("""
                INSERT INTO free_session_quota(session_id, capacity, remaining)
                SELECT s.activity_session_id, s.capacity, s.capacity
                FROM activity_session s JOIN activity a ON a.activity_id = s.activity_id
                WHERE s.activity_session_id = ? AND a.type = 'FREE' AND a.status = 'PUBLISHED'
                ON DUPLICATE KEY UPDATE session_id = free_session_quota.session_id
                """, sessionId);
    }

    private int takeQuota(Long sessionId) {
        return jdbc.update("UPDATE free_session_quota SET remaining = remaining - 1 WHERE session_id = ? AND remaining > 0", sessionId);
    }

    private long insertActive(Long userId, Long sessionId) {
        jdbc.update("INSERT INTO free_reservation(user_id, session_id) VALUES (?, ?)", userId, sessionId);
        return jdbc.queryForObject("SELECT reservation_id FROM free_reservation WHERE user_id = ? AND active_session_id = ?", Long.class, userId, sessionId);
    }

    private long reserve(Long userId, Long sessionId) {
        return tx(() -> {
            initializeQuota(sessionId);
            assertThat(takeQuota(sessionId)).isEqualTo(1);
            return insertActive(userId, sessionId);
        });
    }

    private boolean tryReserve(Long userId) {
        return tx(() -> {
            if (takeQuota(session.getActivitySessionId()) != 1) { return false; }
            insertActive(userId, session.getActivitySessionId()); return true;
        });
    }

    private boolean tryReserveSameAccount() {
        try { reserve(buyer.getId(), session.getActivitySessionId()); return true; }
        catch (DuplicateKeyException failure) {
            assertThat(failure).hasMessageContaining("uq_free_reservation_active"); return false;
        }
    }

    private boolean cancel(long id) {
        return tx(() -> {
            jdbc.queryForObject("SELECT session_id FROM free_session_quota WHERE session_id = ? FOR UPDATE", Long.class, session.getActivitySessionId());
            int changed = jdbc.update("UPDATE free_reservation SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP(3) WHERE reservation_id = ? AND status = 'ACTIVE'", id);
            if (changed == 0) { return false; }
            assertThat(jdbc.update("UPDATE free_session_quota SET remaining = remaining + 1 WHERE session_id = ? AND remaining < capacity", session.getActivitySessionId())).isEqualTo(1);
            return true;
        });
    }

    private int remaining() { return jdbc.queryForObject("SELECT remaining FROM free_session_quota WHERE session_id = ?", Integer.class, session.getActivitySessionId()); }
    private LocalDateTime databaseNow() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class); }
    private void assertNoPaidResources() {
        for (String table : List.of("session_zone", "session_seat", "ticket_order", "ticket")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
        }
    }
    private void assertConsistent() { FreeReservationInvariantAssertions.assertConsistent(jdbc); }
    private List<Map<String, Object>> violations() { return FreeReservationInvariantAssertions.violations(jdbc); }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean IsolatedQualificationMySqlDataSource dataSource(DataSourceProperties properties) throws Exception {
            var source = new IsolatedQualificationMySqlDataSource(properties, 6, List.of(
                    "db/migration/V1__create_schema.sql", "db/migration/V3__create_whitelist_tables.sql"));
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V4__create_free_reservation_tables.sql"));
                return source;
            } catch (Exception failure) { source.close(); throw failure; }
        }
    }
}
