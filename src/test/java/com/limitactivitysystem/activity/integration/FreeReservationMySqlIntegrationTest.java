package com.limitactivitysystem.activity.integration;

import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.reservation.dto.ReservationResponse;
import com.limitactivitysystem.reservation.exception.ReservationException;
import com.limitactivitysystem.reservation.mapper.FreeReservationMapper;
import com.limitactivitysystem.reservation.mapper.FreeSessionQuotaMapper;
import com.limitactivitysystem.reservation.service.FreeReservationService;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.whitelist.model.WhitelistScopeType;
import com.limitactivitysystem.whitelist.service.PublisherWhitelistService;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import(FreeReservationMySqlIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class FreeReservationMySqlIntegrationTest extends PaymentResourceTestSupport {
    @Autowired private FreeReservationService reservations;
    @Autowired private FreeReservationMapper reservationMapper;
    @Autowired private FreeSessionQuotaMapper quotas;
    @Autowired private PublisherWhitelistService whitelist;
    @Autowired private ReservationObservation observation;
    private Activity activity;
    private ActivitySession session;
    private ActivitySession otherSession;
    private final List<Long> concurrentUserIds = new ArrayList<>();

    @BeforeEach void publishOpenFreeScope() throws Exception {
        var now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class);
        activity = insertDraft(publisher, "FREE");
        activity.setOpenTime(now.minusHours(1)); activity.setCloseTime(now.plusHours(1));
        jdbc.update("UPDATE activity SET open_time = ?, close_time = ? WHERE activity_id = ?",
                activity.getOpenTime(), activity.getCloseTime(), activity.getActivityId());
        session = insertSession(activity); otherSession = insertSession(activity); insertSession(activity);
        publish(activity, publisher).andExpect(status().isNoContent());
    }

    @AfterEach void removeOnlyThisSchemasFreeFixtures() {
        observation.current.remove(); SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM free_reservation"); jdbc.update("DELETE FROM free_session_quota");
        jdbc.update("DELETE FROM whitelist_member"); jdbc.update("DELETE FROM whitelist_config");
        concurrentUserIds.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void at01ThreeSessionsAllowTwoBookingsButRejectDuplicateActiveReservations(boolean publisherRole) throws Exception {
        var actor = publisherRole ? publisher : buyer;
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity_session WHERE activity_id = ?",
                Integer.class, activity.getActivityId())).isEqualTo(3);
        assertThat(session.getActivityId()).isEqualTo(otherSession.getActivityId()).isEqualTo(activity.getActivityId());
        var result = createHttp(actor, session.getActivitySessionId());
        assertThat(result.userId()).isEqualTo(actor.getId()); assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.createdAt()).isNotNull(); assertThat(result.cancelledAt()).isNull();
        assertThat(remaining(session.getActivitySessionId())).isEqualTo(9);
        var other = createHttp(actor, otherSession.getActivitySessionId());
        assertThat(other.userId()).isEqualTo(actor.getId()); assertThat(other.status()).isEqualTo("ACTIVE");
        assertThat(other.reservationId()).isNotEqualTo(result.reservationId());
        for (Long id : List.of(session.getActivitySessionId(), otherSession.getActivitySessionId())) {
            var before = snapshot();
            mvc.perform(post("/sessions/{id}/reservations", id).header(HttpHeaders.AUTHORIZATION, bearer(actor)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_ALREADY_ACTIVE"));
            assertThat(snapshot()).isEqualTo(before);
            assertThat(remaining(id)).isEqualTo(9);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM free_reservation WHERE user_id = ? AND session_id = ? AND status = 'ACTIVE'",
                    Integer.class, actor.getId(), id)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM free_reservation WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class, actor.getId())).isEqualTo(2);
        assertNoPaidResources(); consistent();
        evidence("day9-at01-multiple-sessions-" + actor.getRole().toLowerCase(java.util.Locale.ROOT),
                Map.of("activityId", activity.getActivityId(), "sessionCount", 3, "reservations", List.of(result, other),
                        "duplicateRejectedForBothSessions", true, "invariants", "PASS"));
    }

    @Test void at03CancellationAndRebookingKeepHistoryAndAnOldCancelCannotReleaseTheNewQuota() throws Exception {
        var first = createHttp(buyer, session.getActivitySessionId());
        mvc.perform(post("/reservations/{id}/cancel", first.reservationId()).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists());
        assertThat(remaining(session.getActivitySessionId())).isEqualTo(10);
        var second = createHttp(buyer, session.getActivitySessionId());
        assertThat(second.reservationId()).isNotEqualTo(first.reservationId());
        var before = snapshot();
        mvc.perform(post("/reservations/{id}/cancel", first.reservationId()).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_NOT_ACTIVE"));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(reservationMapper.findById(first.reservationId()).getCreatedAt()).isEqualTo(first.createdAt());
        assertThat(reservationMapper.findById(first.reservationId()).getStatus()).isEqualTo("CANCELLED");
        assertThat(reservationMapper.findById(second.reservationId()).getStatus()).isEqualTo("ACTIVE");
        consistent();
        evidence("day9-at03-history", Map.of("first", reservationMapper.findById(first.reservationId()),
                "second", second, "remaining", remaining(session.getActivitySessionId()), "oldCancelHasNoEffect", true));
    }

    @Test void anotherAccountCannotCancelAndMissingIdsReturn404() throws Exception {
        var record = createHttp(buyer, session.getActivitySessionId());
        var before = snapshot();
        mvc.perform(post("/reservations/{id}/cancel", record.reservationId()).header(HttpHeaders.AUTHORIZATION, bearer(otherPublisher)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("RESERVATION_ACCESS_DENIED"));
        mvc.perform(post("/reservations/{id}/cancel", Long.MAX_VALUE).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        mvc.perform(post("/sessions/{id}/reservations", Long.MAX_VALUE).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anAccountDisabledAfterAuthenticationCannotCreateOrCancel(boolean cancel) throws Exception {
        var record = cancel ? createHttp(buyer, session.getActivitySessionId()) : null;
        var before = snapshot();
        asUser(buyer, () -> {
            jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", buyer.getId());
            assertThatThrownBy(() -> { if (cancel) { reservations.cancel(record.reservationId()); }
                else { reservations.create(session.getActivitySessionId()); } })
                    .isInstanceOfSatisfying(ReservationException.class,
                            failure -> assertThat(failure.getErrorCode().name()).isEqualTo("USER_DISABLED"));
            return null;
        });
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void aFailedQuotaRestoreCannotCommitTheCancellation() throws Exception {
        var record = createHttp(buyer, session.getActivitySessionId());
        // Deliberately corrupt only this test's counter to exercise the zero-row restore guard.
        jdbc.update("UPDATE free_session_quota SET remaining = capacity WHERE session_id = ?", session.getActivitySessionId());
        var before = snapshot();
        assertThatThrownBy(() -> asUser(buyer, () -> reservations.cancel(record.reservationId())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("quota could not be restored");
        assertThat(reservationMapper.findById(record.reservationId()).getStatus()).isEqualTo("ACTIVE");
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"PAID", "DRAFT", "CANCELLED", "FINISHED"})
    void activityTypeAndLifecycleAreCheckedBeforeQuotaChanges(String change) throws Exception {
        if (change.equals("PAID")) { jdbc.update("UPDATE activity SET type = 'PAID' WHERE activity_id = ?", activity.getActivityId()); }
        else { jdbc.update("UPDATE activity SET status = ? WHERE activity_id = ?", change, activity.getActivityId()); }
        rejectCreate("PAID".equals(change) ? "ACTIVITY_NOT_FREE" : "ACTIVITY_NOT_PUBLISHED");
    }

    @ParameterizedTest @ValueSource(strings = {"CANCELLED", "FINISHED", "STARTED"})
    void cancelledFinishedAndStartedSessionsCannotTakeQuota(String change) throws Exception {
        if (change.equals("STARTED")) {
            jdbc.update("UPDATE activity_session SET start_time = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE activity_session_id = ?", session.getActivitySessionId());
        } else { jdbc.update("UPDATE activity_session SET status = ? WHERE activity_session_id = ?", change, session.getActivitySessionId()); }
        rejectCreate(change.equals("STARTED") ? "SESSION_STARTED" : "SESSION_NOT_AVAILABLE");
    }

    @Test void whitelistChangesDoNotBlockCancellationButRebookingChecksCurrentEligibility() throws Exception {
        asPublisher(() -> whitelist.enable(WhitelistScopeType.ACTIVITY, activity.getActivityId()));
        asPublisher(() -> whitelist.addMember(WhitelistScopeType.ACTIVITY, activity.getActivityId(), buyer.getId()));
        var first = createHttp(buyer, session.getActivitySessionId());
        asPublisher(() -> { whitelist.removeMember(WhitelistScopeType.ACTIVITY, activity.getActivityId(), buyer.getId()); return null; });
        mvc.perform(post("/reservations/{id}/cancel", first.reservationId()).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isOk());
        rejectCreate("QUALIFICATION_DENIED");
        asPublisher(() -> whitelist.addMember(WhitelistScopeType.ACTIVITY, activity.getActivityId(), buyer.getId()));
        asPublisher(() -> whitelist.enable(WhitelistScopeType.SESSION, session.getActivitySessionId()));
        rejectCreate("QUALIFICATION_DENIED");
        asPublisher(() -> whitelist.addMember(WhitelistScopeType.SESSION, session.getActivitySessionId(), buyer.getId()));
        var next = createHttp(buyer, session.getActivitySessionId());
        assertThat(next.reservationId()).isNotEqualTo(first.reservationId()); consistent();
    }

    @ParameterizedTest @CsvSource({"18:59:59.999,RESERVATION_WINDOW_NOT_OPEN", "19:00:00.000,ACTIVE",
            "19:59:59.999,ACTIVE", "20:00:00.000,RESERVATION_WINDOW_CLOSED", "20:00:00.001,RESERVATION_WINDOW_CLOSED"})
    void creationUsesExactDatabaseWindowBoundaries(String time, String expected) throws Exception {
        fixedScope();
        var probe = probeAt(time);
        var before = snapshot();
        var result = observed(probe, () -> requestHttp("/sessions/" + session.getActivitySessionId() + "/reservations", buyer));
        if (expected.equals("ACTIVE")) {
            assertThat(result.status()).isEqualTo(201);
            var created = result.record();
            assertThat(created.createdAt()).isEqualTo(fixedTime(time)); assertThat(created.status()).isEqualTo("ACTIVE");
            assertThat(probe.connections).hasSize(1); consistent();
        } else {
            assertThat(result.status()).isEqualTo(409);
            assertThat(result.error()).isEqualTo(expected);
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    @ParameterizedTest @CsvSource({"20:05:00.000,CANCELLED", "20:59:59.999,CANCELLED", "21:00:00.000,SESSION_STARTED", "21:00:00.001,SESSION_STARTED"})
    void cancellationIsAllowedAfterSalesCloseButStrictlyBeforeSessionStart(String time, String expected) throws Exception {
        fixedScope();
        var record = observed(probeAt("19:58:00.000"), () -> asUser(buyer, () -> reservations.create(session.getActivitySessionId())));
        var before = snapshot();
        var result = observed(probeAt(time), () -> requestHttp("/reservations/" + record.reservationId() + "/cancel", buyer));
        if (expected.equals("CANCELLED")) {
            assertThat(result.status()).isEqualTo(200);
            var cancelled = result.record();
            assertThat(cancelled.status()).isEqualTo("CANCELLED");
            assertThat(cancelled.cancelledAt()).isEqualTo(fixedTime(time));
            assertThat(remaining(session.getActivitySessionId())).isEqualTo(10); consistent();
        } else {
            assertThat(result.status()).isEqualTo(409);
            assertThat(result.error()).isEqualTo(expected);
            assertThat(reservationMapper.findById(record.reservationId()).getStatus()).isEqualTo("ACTIVE");
            assertThat(remaining(session.getActivitySessionId())).isEqualTo(9);
            assertThat(snapshot()).isEqualTo(before);
        }
        consistent();
        evidence("day9-cancel-boundary-" + time.replace(":", "").replace(".", ""),
                Map.of("databaseTime", fixedTime(time), "startTime", fixedTime("21:00:00.000"),
                        "response", result, "remaining", remaining(session.getActivitySessionId()), "invariants", "PASS"));
    }

    @ParameterizedTest @CsvSource({"false,.takeOne", "false,.insertIfReservable", "true,.cancelIfActiveBeforeStart", "true,.restoreOne"})
    void failuresAfterEveryMutationRollBackQuotaAndReservationTogether(boolean cancel, String suffix) throws Exception {
        tx(() -> quotas.initializeForPublishedFreeSession(session.getActivitySessionId()));
        var record = cancel ? createHttp(buyer, session.getActivitySessionId()) : null;
        var before = snapshot();
        var probe = new Probe(); probe.failSuffix = suffix;
        assertThatThrownBy(() -> observed(probe, () -> asUser(buyer, () -> cancel
                ? reservations.cancel(record.reservationId()) : reservations.create(session.getActivitySessionId()))))
                .isInstanceOf(RuntimeException.class).hasRootCauseInstanceOf(InjectedReservationFailure.class);
        assertThat(probe.injected).isTrue(); assertThat(probe.connections).hasSize(1);
        assertThat(probe.mutationRows.get(suffix.substring(1))).as("failure follows a successful SQL mutation").isEqualTo(1);
        if (suffix.equals(".takeOne")) { assertThat(probe.mutationRows).doesNotContainKey("insertIfReservable"); }
        if (suffix.equals(".cancelIfActiveBeforeStart")) { assertThat(probe.mutationRows).doesNotContainKey("restoreOne"); }
        assertThat(remaining(session.getActivitySessionId())).isEqualTo(cancel ? 9 : 10);
        if (cancel) { assertThat(reservationMapper.findById(record.reservationId()).getStatus()).isEqualTo("ACTIVE"); }
        else { assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM free_reservation", Integer.class)).isZero(); }
        assertThat(snapshot()).isEqualTo(before); consistent();
        evidence("day9-rollback-after-" + suffix.substring(1), Map.of("injectedAfter", suffix,
                "successfulMutationsBeforeFailure", probe.mutationRows, "remaining", remaining(session.getActivitySessionId()),
                "committedReservations", jdbc.queryForList("SELECT * FROM free_reservation ORDER BY reservation_id"),
                "snapshotRestored", true, "invariants", "PASS"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void quotaWaitsCannotCarryCreationOrCancellationAcrossTheirDeadline(boolean cancel) throws Exception {
        fixedScope();
        var record = observed(probeAt("19:58:00.000"), () -> asUser(buyer, () -> reservations.create(session.getActivitySessionId())));
        if (!cancel) { observed(probeAt("19:59:00.000"), () -> asUser(buyer, () -> reservations.cancel(record.reservationId()))); }
        var before = snapshot(); var locked = gate(); var release = gate();
        var holderId = new java.util.concurrent.atomic.AtomicLong();
        var holder = async(() -> tx(() -> { quotas.findBySessionIdForUpdate(session.getActivitySessionId()); holderId.set(connectionId()); locked.countDown(); await(release); return true; }));
        await(locked);
        var probe = probeAt(cancel ? "20:59:59.999" : "19:59:59.999");
        probe.attemptSuffix = cancel ? ".findBySessionIdForUpdate" : ".initializeForPublishedFreeSession";
        probe.reached = gate();
        var attempt = async(() -> observed(probe, () -> asUser(buyer, () -> cancel
                ? reservations.cancel(record.reservationId()) : reservations.create(session.getActivitySessionId()))));
        await(probe.reached); awaitDatabaseLockWait(probe.connectionId, holderId.get(), "free_session_quota");
        probe.clock.set(fixedTime(cancel ? "21:00:00.000" : "20:00:00.000")); release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> attempt.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(ReservationException.class)
                .satisfies(failure -> assertThat(((ReservationException) failure.getCause()).getErrorCode().name())
                        .isEqualTo(cancel ? "SESSION_STARTED" : "RESERVATION_WINDOW_CLOSED"));
        assertThat(snapshot()).isEqualTo(before); consistent();
    }

    @ParameterizedTest @CsvSource({"false,true", "false,false", "true,true", "true,false"})
    void finalSqlAndPostWriteTimeChecksAlsoProtectTheDeadline(boolean cancel, boolean beforeSql) throws Exception {
        fixedScope();
        var record = cancel ? observed(probeAt("19:58:00.000"), () -> asUser(buyer, () -> reservations.create(session.getActivitySessionId()))) : null;
        var before = snapshot();
        var probe = probeAt(cancel ? "20:59:59.999" : "19:59:59.999");
        probe.advanceSuffix = cancel ? (beforeSql ? ".cancelIfActiveBeforeStart" : ".restoreOne") : ".insertIfReservable";
        probe.advanceBefore = beforeSql; probe.advanceTo = fixedTime(cancel ? "21:00:00.000" : "20:00:00.000");
        assertThatThrownBy(() -> observed(probe, () -> asUser(buyer, () -> cancel
                ? reservations.cancel(record.reservationId()) : reservations.create(session.getActivitySessionId()))))
                .isInstanceOfSatisfying(ReservationException.class, failure -> assertThat(failure.getErrorCode().name())
                        .isEqualTo(cancel ? "SESSION_STARTED" : "RESERVATION_WINDOW_CLOSED"));
        assertThat(probe.advanced).isTrue(); assertThat(snapshot()).isEqualTo(before); consistent();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void concurrentCreationHasOneWinnerForTheLastSlotOrForOneAccount(boolean sameAccount) throws Exception {
        if (!sameAccount) { jdbc.update("UPDATE activity_session SET capacity = 1 WHERE activity_session_id = ?", session.getActivitySessionId()); }
        var start = new CyclicBarrier(2);
        var first = async(() -> { start.await(10, TimeUnit.SECONDS); return outcome(buyer, () -> reservations.create(session.getActivitySessionId())); });
        var second = async(() -> { start.await(10, TimeUnit.SECONDS); return outcome(sameAccount ? buyer : otherPublisher, () -> reservations.create(session.getActivitySessionId())); });
        var results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        assertThat(results.stream().filter(result -> result.record() != null)).hasSize(1);
        assertThat(results.stream().filter(result -> result.error() != null).map(Outcome::error)).containsExactly(sameAccount ? "RESERVATION_ALREADY_ACTIVE" : "RESERVATION_QUOTA_EXHAUSTED");
        assertThat(remaining(session.getActivitySessionId())).isEqualTo(sameAccount ? 9 : 0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM free_reservation", Integer.class)).isEqualTo(1);
        consistent(); assertNoPaidResources();
        evidence("day9-api-create-race-" + (sameAccount ? "same-account" : "last-slot"), Map.of("outcomes", results, "invariants", "PASS"));
    }

    @Test void at02TwentyDifferentAccountsPostingForTheLastSlotHaveExactlyOneWinner() throws Exception {
        int concurrency = 20;
        jdbc.update("UPDATE activity_session SET capacity = 1 WHERE activity_session_id = ?", session.getActivitySessionId());
        tx(() -> quotas.initializeForPublishedFreeSession(session.getActivitySessionId()));
        assertThat(remaining(session.getActivitySessionId())).isEqualTo(1);
        var actors = new ArrayList<AppUser>();
        for (int i = 0; i < concurrency; i++) {
            var actor = new AppUser();
            actor.setUsername("day9_at02_" + UUID.randomUUID().toString().replace("-", ""));
            actor.setPasswordHash(buyer.getPasswordHash());
            assertThat(userMapper.insert(actor)).isEqualTo(1);
            concurrentUserIds.add(actor.getId()); actors.add(actor);
        }
        assertThat(actors.stream().map(AppUser::getId)).doesNotHaveDuplicates().hasSize(concurrency);
        var probes = actors.stream().map(actor -> new Probe()).toList();
        var start = new CyclicBarrier(concurrency);
        var results = concurrentHttp(actors, probes, start, ".initializeForPublishedFreeSession",
                "/sessions/" + session.getActivitySessionId() + "/reservations");
        assertThat(results.stream().filter(result -> result.status() == 201)).hasSize(1);
        assertThat(results.stream().filter(result -> result.status() != 201)).hasSize(19)
                .allSatisfy(result -> {
                    assertThat(result.status()).isEqualTo(409);
                    assertThat(result.error()).isEqualTo("RESERVATION_QUOTA_EXHAUSTED");
                });
        assertThat(probes.stream().map(probe -> probe.connectionId)).doesNotHaveDuplicates().hasSize(concurrency);
        assertThat(remaining(session.getActivitySessionId())).isZero();
        int active = jdbc.queryForObject("SELECT COUNT(*) FROM free_reservation WHERE session_id = ? AND status = 'ACTIVE'",
                Integer.class, session.getActivitySessionId());
        assertThat(active).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM free_reservation", Integer.class)).isEqualTo(1);
        consistent(); assertNoPaidResources();
        evidence("day9-at02-last-slot-c20", Map.of("accounts", concurrentUserIds, "concurrency", concurrency,
                "distinctTransactionConnections", probes.stream().map(probe -> probe.connectionId).toList(),
                "initialRemaining", 1, "success", 1, "remaining", remaining(session.getActivitySessionId()),
                "activeReservations", active, "responses", results, "invariants", "PASS"));
    }

    @Test void databaseUniqueKeyRejectsConcurrentHttpDuplicatesEvenWhenThePrecheckMisses() throws Exception {
        var first = new Probe(); first.hideActiveReservation = true;
        var second = new Probe(); second.hideActiveReservation = true;
        var probes = List.of(first, second);
        var results = concurrentHttp(List.of(buyer, buyer), probes, new CyclicBarrier(2),
                ".initializeForPublishedFreeSession", "/sessions/" + session.getActivitySessionId() + "/reservations");
        assertThat(results.stream().filter(result -> result.status() == 201)).hasSize(1);
        assertThat(results.stream().filter(result -> result.status() == 409).map(HttpOutcome::error))
                .containsExactly("RESERVATION_ALREADY_ACTIVE");
        assertThat(probes.stream().filter(probe -> probe.existingReservationHidden)).hasSize(1);
        assertThat(probes).allSatisfy(probe -> assertThat(probe.mutationRows.get("takeOne")).isEqualTo(1));
        assertThat(remaining(session.getActivitySessionId())).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM free_reservation WHERE user_id = ? AND session_id = ? AND status = 'ACTIVE'",
                Integer.class, buyer.getId(), session.getActivitySessionId())).isEqualTo(1);
        consistent();
        evidence("day9-http-active-key-race", Map.of("userId", buyer.getId(), "sessionId", session.getActivitySessionId(),
                "precheckMissedExisting", true, "bothTransactionsDeductedBeforeInsert", true,
                "remaining", remaining(session.getActivitySessionId()), "responses", results, "invariants", "PASS"));
    }

    @Test void twoConcurrentCancellationsReleaseExactlyOneSlot() throws Exception {
        var record = createHttp(buyer, session.getActivitySessionId());
        var probes = List.of(new Probe(), new Probe());
        var results = concurrentHttp(List.of(buyer, buyer), probes, new CyclicBarrier(2),
                ".findBySessionIdForUpdate", "/reservations/" + record.reservationId() + "/cancel");
        assertThat(results.stream().filter(result -> result.status() == 200).map(result -> result.record().status()))
                .containsExactly("CANCELLED");
        assertThat(results.stream().filter(result -> result.status() == 409).map(HttpOutcome::error)).containsExactly("RESERVATION_NOT_ACTIVE");
        assertThat(probes.stream().filter(probe -> probe.mutationRows.containsKey("restoreOne")))
                .singleElement().satisfies(probe -> assertThat(probe.mutationRows.get("restoreOne")).isEqualTo(1));
        assertThat(reservationMapper.findById(record.reservationId()).getStatus()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM free_reservation WHERE status = 'ACTIVE'", Integer.class)).isZero();
        assertThat(remaining(session.getActivitySessionId())).isEqualTo(10); consistent();
        evidence("day9-http-cancel-race", Map.of("reservationId", record.reservationId(), "responses", results,
                "initialRemaining", 9, "remaining", remaining(session.getActivitySessionId()), "restoreExecutions", 1, "invariants", "PASS"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void at03ReleasedSlotIsOpenToBothFormerOwnerAndOtherAccount(boolean formerOwnerWins) throws Exception {
        jdbc.update("UPDATE activity_session SET capacity = 1 WHERE activity_session_id = ?", session.getActivitySessionId());
        var old = createHttp(buyer, session.getActivitySessionId());
        var cancelled = requestHttp("/reservations/" + old.reservationId() + "/cancel", buyer);
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.record().status()).isEqualTo("CANCELLED");
        assertThat(remaining(session.getActivitySessionId())).isEqualTo(1);
        AppUser winner = formerOwnerWins ? buyer : otherPublisher;
        AppUser loser = formerOwnerWins ? otherPublisher : buyer;
        var holder = new Probe(); holder.commitSuffix = ".insertIfReservable"; holder.reached = gate(); holder.release = gate();
        String path = "/sessions/" + session.getActivitySessionId() + "/reservations";
        var first = async(() -> observed(holder, () -> requestHttp(path, winner)));
        await(holder.reached);
        var waiter = new Probe(); waiter.attemptSuffix = ".initializeForPublishedFreeSession"; waiter.reached = gate();
        var second = async(() -> observed(waiter, () -> requestHttp(path, loser)));
        try {
            await(waiter.reached); awaitDatabaseLockWait(waiter.connectionId, holder.connectionId, "free_session_quota");
        } finally { holder.release.countDown(); }
        var won = first.get(10, TimeUnit.SECONDS); var lost = second.get(10, TimeUnit.SECONDS);
        assertThat(won.status()).isEqualTo(201);
        assertThat(won.record().userId()).isEqualTo(winner.getId());
        assertThat(won.record().reservationId()).isNotEqualTo(old.reservationId());
        assertThat(lost.status()).isEqualTo(409); assertThat(lost.error()).isEqualTo("RESERVATION_QUOTA_EXHAUSTED");
        var history = reservationMapper.findById(old.reservationId());
        assertThat(history.getStatus()).isEqualTo("CANCELLED");
        assertThat(history.getCreatedAt()).isEqualTo(old.createdAt());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM free_reservation WHERE session_id = ? AND status = 'ACTIVE'",
                Integer.class, session.getActivitySessionId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM free_reservation WHERE session_id = ? AND status = 'CANCELLED'",
                Integer.class, session.getActivitySessionId())).isEqualTo(1);
        assertThat(remaining(session.getActivitySessionId())).isZero(); consistent();
        evidence("day9-at03-public-pool-" + (formerOwnerWins ? "former-owner" : "other-account"),
                Map.of("cancelledHistory", history, "remainingAfterCancel", 1, "remainingAfterCompetition", 0,
                        "formerOwnerWon", formerOwnerWins, "winner", won, "loser", lost, "invariants", "PASS"));
    }

    private ReservationResponse createHttp(AppUser actor, Long sessionId) throws Exception {
        var response = mvc.perform(post("/sessions/{id}/reservations", sessionId).header(HttpHeaders.AUTHORIZATION, bearer(actor)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.expiresAt").doesNotExist()).andReturn().getResponse();
        return objectMapper.readValue(response.getContentAsString(), ReservationResponse.class);
    }
    private HttpOutcome requestHttp(String path, AppUser actor) throws Exception {
        var response = mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer(actor))).andReturn().getResponse();
        if (response.getStatus() >= 200 && response.getStatus() < 300) {
            return new HttpOutcome(response.getStatus(), objectMapper.readValue(response.getContentAsString(), ReservationResponse.class), null);
        }
        return new HttpOutcome(response.getStatus(), null, objectMapper.readTree(response.getContentAsString()).path("code").asText());
    }
    private List<HttpOutcome> concurrentHttp(List<AppUser> actors, List<Probe> probes, CyclicBarrier ready,
                                             String beforeSql, String path) throws Exception {
        // Hold every request before its first quota lock so all transactions really overlap.
        var workers = Executors.newFixedThreadPool(actors.size());
        try {
            var futures = new ArrayList<java.util.concurrent.Future<HttpOutcome>>();
            for (int i = 0; i < actors.size(); i++) {
                var actor = actors.get(i); var probe = probes.get(i);
                probe.ready = ready; probe.beforeSql = beforeSql;
                futures.add(workers.submit(() -> observed(probe, () -> requestHttp(path, actor))));
            }
            var results = new ArrayList<HttpOutcome>();
            for (var future : futures) { results.add(future.get(30, TimeUnit.SECONDS)); }
            return results;
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
    private void rejectCreate(String code) throws Exception {
        var before = snapshot();
        mvc.perform(post("/sessions/{id}/reservations", session.getActivitySessionId()).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().is(code.equals("ACTIVITY_NOT_FREE") ? 400 : code.equals("QUALIFICATION_DENIED") ? 403 : 409))
                .andExpect(jsonPath("$.code").value(code));
        assertThat(snapshot()).isEqualTo(before);
    }
    private <T> T asUser(AppUser actor, Callable<T> work) throws Exception {
        var token = jwtDecoder.decode(JwtTestTokens.token(jwtEncoder, actor.getId(), actor.getRole()));
        var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(authenticationConverter.convert(token));
        SecurityContextHolder.setContext(context);
        try { return work.call(); } finally { SecurityContextHolder.clearContext(); }
    }
    private Outcome outcome(AppUser actor, Callable<ReservationResponse> work) throws Exception {
        try { return new Outcome(asUser(actor, work), null); }
        catch (ReservationException failure) { return new Outcome(null, failure.getErrorCode().name()); }
    }
    private <T> T observed(Probe probe, Callable<T> work) throws Exception {
        observation.current.set(probe);
        try { return work.call(); } finally { observation.current.remove(); }
    }
    private List<List<Map<String, Object>>> snapshot() {
        return List.of("free_reservation", "free_session_quota", "session_zone", "session_seat", "ticket_order", "ticket", "user_session_purchase")
                .stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1")).toList();
    }
    private void assertNoPaidResources() {
        for (String table : List.of("session_zone", "session_seat", "ticket_order", "ticket", "user_session_purchase")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
        }
    }
    private void consistent() { FreeReservationInvariantAssertions.assertConsistent(jdbc); }
    private int remaining(Long sessionId) { return jdbc.queryForObject("SELECT remaining FROM free_session_quota WHERE session_id = ?", Integer.class, sessionId); }
    private void fixedScope() {
        jdbc.update("UPDATE activity SET open_time = ?, close_time = ? WHERE activity_id = ?", fixedTime("19:00:00.000"), fixedTime("20:00:00.000"), activity.getActivityId());
        jdbc.update("UPDATE activity_session SET start_time = ?, end_time = ? WHERE activity_session_id = ?", fixedTime("21:00:00.000"), fixedTime("23:00:00.000"), session.getActivitySessionId());
    }
    private static LocalDateTime fixedTime(String time) { return LocalDate.of(2026, 10, 28).atTime(LocalTime.parse(time)); }
    private Probe probeAt(String time) { var probe = new Probe(); probe.clock.set(fixedTime(time)); return probe; }

    record Outcome(ReservationResponse record, String error) { }
    record HttpOutcome(int status, ReservationResponse record, String error) { }
    static class Probe {
        final AtomicReference<LocalDateTime> clock = new AtomicReference<>();
        final Set<Long> connections = new HashSet<>();
        String failSuffix; boolean injected;
        String attemptSuffix; String commitSuffix;
        CountDownLatch reached; CountDownLatch release;
        long connectionId;
        String advanceSuffix; boolean advanceBefore; boolean advanced; LocalDateTime advanceTo;
        String beforeSql; CyclicBarrier ready;
        boolean hideActiveReservation; boolean existingReservationHidden;
        final Map<String, Integer> mutationRows = new LinkedHashMap<>();
    }

    @Intercepts({@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
            @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})})
    static class ReservationObservation implements Interceptor {
        final ThreadLocal<Probe> current = new ThreadLocal<>();
        private final JdbcTemplate jdbc;
        ReservationObservation(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            Probe probe = current.get();
            if (probe == null || !TransactionSynchronizationManager.isActualTransactionActive()) { return invocation.proceed(); }
            String id = ((MappedStatement) invocation.getArgs()[0]).getId();
            if (probe.advanceSuffix != null && probe.advanceBefore && id.endsWith(probe.advanceSuffix)) {
                probe.clock.set(probe.advanceTo); probe.advanced = true;
            }
            applyClock(probe);
            probe.connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class); probe.connections.add(probe.connectionId);
            if (probe.attemptSuffix != null && id.endsWith(probe.attemptSuffix)) { probe.reached.countDown(); }
            if (probe.beforeSql != null && id.endsWith(probe.beforeSql)) { probe.ready.await(15, TimeUnit.SECONDS); }
            Object result = invocation.proceed();
            if (result instanceof Integer affected && id.startsWith("com.limitactivitysystem.reservation.mapper.")) {
                probe.mutationRows.put(id.substring(id.lastIndexOf('.') + 1), affected);
            }
            if (probe.hideActiveReservation && id.endsWith(".findActive")) {
                probe.existingReservationHidden = !((List<?>) result).isEmpty();
                return List.of();
            }
            if (probe.failSuffix != null && id.endsWith(probe.failSuffix)) { probe.injected = true; throw new InjectedReservationFailure(); }
            if (probe.advanceSuffix != null && !probe.advanceBefore && id.endsWith(probe.advanceSuffix)) {
                probe.clock.set(probe.advanceTo); probe.advanced = true;
            }
            if (probe.commitSuffix != null && id.endsWith(probe.commitSuffix)) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) { probe.reached.countDown(); await(probe.release); }
                });
            }
            return result;
        }
        private void applyClock(Probe probe) {
            if (probe.clock.get() == null) { return; }
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                try (var sql = connection.prepareStatement("SET timestamp = UNIX_TIMESTAMP(?)")) {
                    sql.setString(1, probe.clock.get().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))); sql.execute();
                }
                if (TransactionSynchronizationManager.getSynchronizations().stream().noneMatch(sync -> sync instanceof ClockReset)) {
                    TransactionSynchronizationManager.registerSynchronization(new ClockReset(connection));
                }
                return null;
            });
        }
    }
    private record ClockReset(Connection connection) implements TransactionSynchronization {
        @Override public void afterCompletion(int status) {
            try (var sql = connection.createStatement()) { sql.execute("SET timestamp = DEFAULT"); }
            catch (SQLException failure) { throw new IllegalStateException(failure); }
        }
    }
    private static class InjectedReservationFailure extends RuntimeException { }
    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean IsolatedQualificationMySqlDataSource dataSource(DataSourceProperties properties) throws Exception {
            var source = new IsolatedQualificationMySqlDataSource(properties, 22);
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V4__create_free_reservation_tables.sql")); return source;
            } catch (Exception failure) { source.close(); throw failure; }
        }
        @Bean ReservationObservation reservationObservation(JdbcTemplate jdbc) { return new ReservationObservation(jdbc); }
    }
}
