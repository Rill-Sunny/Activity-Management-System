package com.limitactivitysystem.activity.integration;

import com.limitactivitysystem.activity.model.*;
import com.limitactivitysystem.gift.dto.*;
import com.limitactivitysystem.gift.exception.*;
import com.limitactivitysystem.gift.mapper.GiftInventoryMapper;
import com.limitactivitysystem.gift.service.GiftAllocationService;
import com.limitactivitysystem.gift.service.GiftAllocationQueryService;
import com.limitactivitysystem.security.JwtTestTokens;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import({GiftInventorySchemaMySqlIntegrationTest.DatabaseConfiguration.class, GiftAllocationMySqlIntegrationTest.ProbeConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class GiftAllocationMySqlIntegrationTest extends PaymentResourceTestSupport {
    private static final Map<String, Object> RECONCILIATION = new ConcurrentSkipListMap<>();
    @Autowired private GiftObservation observation;
    @Autowired private GiftAllocationService allocations;
    @Autowired private GiftInventoryMapper inventory;
    @Autowired private GiftAllocationQueryService queries;
    private Activity activity;
    private ActivitySession session;
    private SessionZone gift, otherGift, paid;
    private List<SessionSeat> giftSeats;
    private boolean deliberatelyCorrupt;

    @BeforeAll static void resetAudit() { RECONCILIATION.clear(); }
    @AfterAll static void saveAudit() throws Exception {
        var path = Path.of("target", "verification", "day12-gift-final-reconciliation.json"); Files.createDirectories(path.getParent());
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(),
                Map.of("script", "tests/sql/gift_invariants.sql", "testCases", RECONCILIATION.size(), "checks", RECONCILIATION));
    }

    @BeforeEach void publishSeparateResourcePools() throws Exception {
        var now = now(); activity = insertDraft(publisher, "PAID"); session = insertSession(activity);
        jdbc.update("UPDATE activity SET open_time = ?, close_time = ? WHERE activity_id = ?", now.minusDays(10), now.plusDays(1), activity.getActivityId());
        jdbc.update("UPDATE activity_session SET start_time = ?, end_time = ?, max_tickets_per_user = 10 WHERE activity_session_id = ?",
                now.plusDays(20), now.plusDays(20).plusHours(2), session.getActivitySessionId());
        gift = insertZone(session, "GIFT", 6, 6); otherGift = insertZone(session, "GIFT", 2, 2); paid = insertZone(session, "PAID", 2, 2);
        giftSeats = seatMapper.findByZoneId(gift.getZoneId());
        // Priority order differs from Seat ID order, including equal-priority row/seat tie breaks.
        jdbc.update("UPDATE session_seat SET row_no = 2, seat_no = 1 WHERE seat_id = ?", giftSeats.get(2).getSeatId());
        jdbc.update("UPDATE session_seat SET seat_no = 3 WHERE seat_id = ?", giftSeats.get(3).getSeatId());
        jdbc.update("UPDATE session_seat SET seat_no = 4 WHERE seat_id = ?", giftSeats.get(5).getSeatId());
        for (int i = 0; i < 6; i++) {
            jdbc.update("UPDATE session_seat SET allocation_priority = ? WHERE seat_id = ?", i == 0 ? 30 : i == 4 ? 20 : 10, giftSeats.get(i).getSeatId());
        }
        publish(activity, publisher).andExpect(status().isNoContent());
    }

    @AfterEach void reconcileBeforeRemovingOnlyOwnedFixtures(TestInfo test) throws Exception {
        observation.current.remove();
        try {
            var differences = giftViolations();
            RECONCILIATION.put(test.getTestMethod().orElseThrow().getName() + " :: " + test.getDisplayName(),
                    Map.of("deliberatelyCorrupt", deliberatelyCorrupt, "violations", differences.size(),
                            "types", differences.stream().map(row -> row.get("violation")).distinct().sorted().toList()));
            if (deliberatelyCorrupt) { assertThat(differences).isNotEmpty(); }
            else { assertThat(differences).isEmpty(); }
            // PAID resources are checked independently, including allocation/payment concurrency.
            if (!deliberatelyCorrupt) { RefundInvariantAssertions.assertConsistent(jdbc); }
        } finally {
            jdbc.update("DELETE FROM gift_inventory_operation_item"); jdbc.update("DELETE FROM gift_inventory_operation");
            if (session != null) { jdbc.update("DELETE FROM whitelist_config WHERE scope_type = 'SESSION' AND scope_id = ?", session.getActivitySessionId()); }
            if (gift != null && otherGift != null && paid != null) {
                jdbc.update("DELETE FROM session_seat WHERE zone_id IN (?, ?, ?)", gift.getZoneId(), otherGift.getZoneId(), paid.getZoneId());
                jdbc.update("DELETE FROM session_zone WHERE zone_id IN (?, ?, ?)", gift.getZoneId(), otherGift.getZoneId(), paid.getZoneId());
            }
            if (session != null) { jdbc.update("DELETE FROM activity_session WHERE activity_session_id = ?", session.getActivitySessionId()); }
        }
    }

    @Test void allocateFiveRestoresNoPaidResourcesAndPersistsExactSeatHistory() throws Exception {
        var beforePaid = paidSnapshot(); var probe = new Probe(); observation.current.set(probe);
        GiftAllocationResponse result;
        try { result = allocate(gift.getZoneId(), "gift-op-001", 5, 201); } finally { observation.current.remove(); }
        assertThat(result.publisherId()).isEqualTo(publisher.getId()); assertThat(result.sessionId()).isEqualTo(session.getActivitySessionId());
        assertThat(result.zoneId()).isEqualTo(gift.getZoneId()); assertThat(result.quantity()).isEqualTo(5);
        assertThat(result.operationType()).isEqualTo("ALLOCATE"); assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.createdAt()).isNotNull();
        assertThat(result.seatIds()).containsExactlyElementsOf(giftSeats.subList(1, 6).stream().map(SessionSeat::getSeatId).sorted().toList());
        assertThat(zoneMapper.findById(gift.getZoneId()).getRemainsAmount()).isEqualTo(1);
        assertThat(zoneMapper.findById(gift.getZoneId()).getTotalAmount()).isEqualTo(6);
        assertThat(seatMapper.findById(giftSeats.get(0).getSeatId()).getAllocationStatus()).isEqualTo("AVAILABLE");
        result.seatIds().forEach(id -> assertThat(seatMapper.findById(id).getAllocationStatus()).isEqualTo("GIFT_ALLOCATED"));
        assertThat(probe.occupied).containsExactlyElementsOf(result.seatIds()).isSorted();
        assertThat(paidSnapshot()).isEqualTo(beforePaid); assertThat(giftViolations()).isEmpty();
        evidence("day12-gift-allocate-five", Map.of("response", result, "stockRemaining", 1, "totalAmount", 6,
                "occupiedSeatOrder", probe.occupied, "paidResourcesUnchanged", true));
    }

    @ParameterizedTest @ValueSource(ints = {3, 4})
    void choicePriorityIsDistinctFromTheAscendingSeatLockOrder(int quantity) throws Exception {
        var expected = quantity == 3 ? List.of(giftSeats.get(1).getSeatId(), giftSeats.get(3).getSeatId(), giftSeats.get(5).getSeatId())
                : List.of(giftSeats.get(1).getSeatId(), giftSeats.get(2).getSeatId(), giftSeats.get(3).getSeatId(), giftSeats.get(5).getSeatId());
        assertThat(allocate(gift.getZoneId(), "priority", quantity, 201).seatIds()).containsExactlyElementsOf(expected);
    }

    @Test void replayAfterExhaustionAndSessionStartReturnsTheSameOperationAndNoWrites() throws Exception {
        var original = allocate(gift.getZoneId(), "replay", 6, 201); var before = snapshot();
        assertThat(allocate(gift.getZoneId(), "replay", 6, 200)).isEqualTo(original); assertThat(snapshot()).isEqualTo(before);
        jdbc.update("UPDATE activity_session SET start_time = ?, end_time = ?, status = 'FINISHED' WHERE activity_session_id = ?",
                now().minusHours(2), now().minusHours(1), session.getActivitySessionId());
        jdbc.update("UPDATE activity SET status = 'FINISHED' WHERE activity_id = ?", activity.getActivityId());
        before = snapshot(); assertThat(allocate(gift.getZoneId(), "replay", 6, 200)).isEqualTo(original);
        assertThat(snapshot()).isEqualTo(before);
        reject(gift.getZoneId(), "new", 1, 409, "GIFT_SCOPE_UNAVAILABLE");
    }

    @ParameterizedTest @ValueSource(strings = {"QUANTITY", "ZONE"})
    void reusedRequestKeysMustRetainTheEntireAllocationIntent(String changed) throws Exception {
        allocate(gift.getZoneId(), "same-key", 2, 201); var before = snapshot();
        reject(changed.equals("ZONE") ? otherGift.getZoneId() : gift.getZoneId(), "same-key", changed.equals("QUANTITY") ? 3 : 2,
                409, "GIFT_IDEMPOTENCY_CONFLICT");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void anotherPublisherCanReuseTheKeyOnlyForTheirOwnGiftResources() throws Exception {
        allocate(gift.getZoneId(), "publisher-key", 1, 201);
        var otherActivity = insertDraft(otherPublisher, "PAID"); var otherSession = insertSession(otherActivity);
        jdbc.update("UPDATE activity_session SET capacity = 2, max_tickets_per_user = 1 WHERE activity_session_id = ?", otherSession.getActivitySessionId());
        var ownedGift = insertZone(otherSession, "GIFT", 1, 1); insertZone(otherSession, "PAID", 1, 1);
        publish(otherActivity, otherPublisher).andExpect(status().isNoContent());
        var response = postJson(path(ownedGift.getZoneId()), otherPublisher, Map.of("requestId", "publisher-key", "quantity", 1))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var saved = objectMapper.readValue(response.getContentAsString(), GiftAllocationResponse.class);
        assertThat(saved.publisherId()).isEqualTo(otherPublisher.getId()); assertThat(saved.zoneId()).isEqualTo(ownedGift.getZoneId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gift_inventory_operation WHERE request_id = 'publisher-key'", Integer.class)).isEqualTo(2);
    }

    @Test void replayStillChecksCurrentOwnership() throws Exception {
        allocate(gift.getZoneId(), "owner-replay", 1, 201);
        jdbc.update("UPDATE activity SET publisher_id = ? WHERE activity_id = ?", otherPublisher.getId(), activity.getActivityId());
        var before = snapshot(); reject(gift.getZoneId(), "owner-replay", 1, 403, "GIFT_ACCESS_DENIED"); assertThat(snapshot()).isEqualTo(before);
    }

    @Test void insufficientGiftInventoryNeverBorrowsFromAnotherZoneOrPaidSeats() throws Exception {
        allocate(gift.getZoneId(), "all", 6, 201); var before = snapshot();
        reject(gift.getZoneId(), "one-more", 1, 409, "GIFT_INSUFFICIENT_INVENTORY"); assertThat(snapshot()).isEqualTo(before);
        assertThat(zoneMapper.findById(otherGift.getZoneId()).getRemainsAmount()).isEqualTo(2);
        assertThat(zoneMapper.findById(paid.getZoneId()).getRemainsAmount()).isEqualTo(2);
    }

    @Test void historyListUsesTheZoneAndCursorAndDetailReturnsTheOriginalOperation() throws Exception {
        var first = allocate(gift.getZoneId(), "list-first", 1, 201);
        allocate(otherGift.getZoneId(), "list-other-zone", 1, 201);
        var last = allocate(gift.getZoneId(), "list-last", 2, 201);
        var before = snapshot();
        var page = history(gift.getZoneId(), "?limit=1");
        assertThat(page.allocations()).containsExactly(first); assertThat(page.nextAfterOperationId()).isEqualTo(first.operationId());
        var next = history(gift.getZoneId(), "?limit=1&afterOperationId=" + page.nextAfterOperationId());
        assertThat(next.allocations()).containsExactly(last); assertThat(next.nextAfterOperationId()).isNull();
        var end = history(gift.getZoneId(), "?afterOperationId=" + last.operationId());
        assertThat(end.allocations()).isEmpty(); assertThat(end.nextAfterOperationId()).isNull();
        assertThat(history(gift.getZoneId(), "").allocations()).containsExactly(first, last);
        assertThat(detail(first.operationId())).isEqualTo(first); assertThat(detail(last.operationId())).isEqualTo(last);
        assertThat(snapshot()).isEqualTo(before);
        evidence("day12-gift-history-query", Map.of("firstPage", page, "lastPage", next, "detail", detail(first.operationId()), "resourceAndHistorySnapshotUnchanged", true));
    }

    @Test void historyIsQueryableForEmptyAndFinishedOwnedGiftZones() throws Exception {
        assertThat(history(gift.getZoneId(), "").allocations()).isEmpty();
        var original = allocate(gift.getZoneId(), "history-before-finish", 1, 201);
        jdbc.update("UPDATE activity SET status = 'FINISHED' WHERE activity_id = ?", activity.getActivityId());
        jdbc.update("UPDATE activity_session SET status = 'FINISHED' WHERE activity_session_id = ?", session.getActivitySessionId());
        var before = snapshot(); assertThat(detail(original.operationId())).isEqualTo(original);
        assertThat(history(gift.getZoneId(), "").allocations()).containsExactly(original); assertThat(snapshot()).isEqualTo(before);
    }

    @Test void historyQueriesRequireTheCurrentOwningPublisherAndNeverExposeOtherZones() throws Exception {
        var original = allocate(gift.getZoneId(), "history-owner", 1, 201); var before = snapshot();
        for (String path : List.of(path(gift.getZoneId()), "/publisher/gift-allocations/" + original.operationId())) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(buyer))).andExpect(status().isForbidden());
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(otherPublisher))).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("GIFT_ACCESS_DENIED"));
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void currentZoneOwnerCanAuditTheOriginalOperatorWithoutRewritingHistory() throws Exception {
        var original = allocate(gift.getZoneId(), "operator-history", 1, 201);
        jdbc.update("UPDATE activity SET publisher_id = ? WHERE activity_id = ?", otherPublisher.getId(), activity.getActivityId());
        var before = snapshot();
        mvc.perform(get("/publisher/gift-allocations/{id}", original.operationId()).header(HttpHeaders.AUTHORIZATION, bearer(publisher)))
                .andExpect(status().isForbidden());
        var response = mvc.perform(get("/publisher/gift-allocations/{id}", original.operationId()).header(HttpHeaders.AUTHORIZATION, bearer(otherPublisher)))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(objectMapper.readValue(response.getContentAsString(), GiftAllocationResponse.class)).isEqualTo(original);
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @CsvSource({"USER,true", "USER,false", "DISABLED,true", "DISABLED,false"})
    void historyServicesRecheckTheCurrentAccountRoleAndStatus(String changed, boolean list) throws Exception {
        var original = allocate(gift.getZoneId(), "read-role", 1, 201);
        assertThatThrownBy(() -> asPublisher(() -> {
            jdbc.update("UPDATE app_user SET " + (changed.equals("USER") ? "role" : "status") + " = ? WHERE id = ?", changed, publisher.getId());
            return list ? queries.list(gift.getZoneId(), 20, 0) : queries.get(original.operationId());
        })).isInstanceOfSatisfying(GiftException.class, failure -> assertThat(failure.getErrorCode().name())
                .isEqualTo(changed.equals("USER") ? "GIFT_ACCESS_DENIED" : "GIFT_USER_DISABLED"));
    }

    @ParameterizedTest @ValueSource(strings = {"?limit=0", "?limit=101", "?limit=-1", "?limit=abc", "?afterOperationId=-1", "?afterOperationId=abc"})
    void historyPaginationRejectsInvalidLimitsAndCursors(String query) throws Exception {
        var before = snapshot();
        mvc.perform(get(path(gift.getZoneId()) + query).header(HttpHeaders.AUTHORIZATION, bearer(publisher)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_GIFT_REQUEST"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void missingNonGiftAndInvalidHistoryPathsReturnDefinedErrors() throws Exception {
        mvc.perform(get(path(Long.MAX_VALUE)).header(HttpHeaders.AUTHORIZATION, bearer(publisher)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GIFT_ZONE_NOT_FOUND"));
        mvc.perform(get(path(paid.getZoneId())).header(HttpHeaders.AUTHORIZATION, bearer(publisher)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GIFT_ZONE_NOT_GIFT"));
        mvc.perform(get("/publisher/gift-allocations/{id}", Long.MAX_VALUE).header(HttpHeaders.AUTHORIZATION, bearer(publisher)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GIFT_OPERATION_NOT_FOUND"));
        for (String id : List.of("0", "-1", "abc")) {
            for (String path : List.of("/publisher/gift-allocations/" + id, "/publisher/gift-zones/" + id + "/allocations")) {
                mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(publisher)))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_GIFT_REQUEST"));
            }
        }
    }

    @Test void historyComesFromOperationItemsEvenIfTheCurrentSeatFactIsCorrupted() throws Exception {
        var original = allocate(gift.getZoneId(), "historical-fact", 2, 201); deliberatelyCorrupt = true;
        jdbc.update("UPDATE session_seat SET allocation_status = 'AVAILABLE' WHERE seat_id = ?", original.seatIds().get(0));
        var before = snapshot(); assertThat(detail(original.operationId())).isEqualTo(original);
        assertThat(history(gift.getZoneId(), "").allocations()).containsExactly(original); assertThat(snapshot()).isEqualTo(before);
        assertThat(giftViolations()).isNotEmpty();
        evidence("day12-gift-history-not-seat-state", Map.of("originalOperation", original, "returnedHistory", detail(original.operationId()),
                "seatFactDeliberatelyCorrupted", true, "historyUnchanged", true, "inconsistencyDetected", true));
    }

    @Test void incompleteOperationDetailsAreExposedInsteadOfReturningPartialHistory() throws Exception {
        var original = allocate(gift.getZoneId(), "missing-detail", 2, 201); deliberatelyCorrupt = true;
        jdbc.update("DELETE FROM gift_inventory_operation_item WHERE operation_id = ? AND seat_id = ?", original.operationId(), original.seatIds().get(0));
        var before = snapshot();
        for (String path : List.of(path(gift.getZoneId()), "/publisher/gift-allocations/" + original.operationId())) {
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(publisher))).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("GIFT_RESOURCE_CONFLICT"));
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void at06RecordsOnlyTheOperatorAndRealSeatsWithoutReceiverAccountsOrTickets() throws Exception {
        int accountsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class);
        var original = allocate(gift.getZoneId(), "at06-no-receiver", 2, 201);
        var response = mvc.perform(get("/publisher/gift-allocations/{id}", original.operationId()).header(HttpHeaders.AUTHORIZATION, bearer(publisher)))
                .andExpect(status().isOk()).andReturn().getResponse();
        var json = objectMapper.readTree(response.getContentAsString());
        assertThat(json.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("operationId", "requestId", "publisherId", "sessionId", "zoneId",
                "operationType", "quantity", "status", "createdAt", "seatIds");
        assertThat(json.path("publisherId").asLong()).isEqualTo(publisher.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(accountsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_session_purchase", Integer.class)).isZero();
        evidence("day12-at06-no-receiver-binding", Map.of("operation", original, "accountCountUnchanged", true, "paidTickets", 0, "purchaseCounterRows", 0));
    }

    @ParameterizedTest @CsvSource({"PAID,PAID_STOCK", "GIFT,GIFT_STOCK", "SESSION,SESSION_CAPACITY"})
    void capacityInspectionFindsEachBrokenCapacityEquation(String kind, String expected) throws Exception {
        deliberatelyCorrupt = true;
        switch (kind) {
            case "PAID" -> jdbc.update("UPDATE session_zone SET remains_amount = 1 WHERE zone_id = ?", paid.getZoneId());
            case "GIFT" -> jdbc.update("UPDATE session_zone SET remains_amount = 5 WHERE zone_id = ?", gift.getZoneId());
            default -> {
                allocate(gift.getZoneId(), "capacity-is-planning", 5, 201);
                jdbc.update("UPDATE activity_session SET capacity = 9, max_tickets_per_user = 9 WHERE activity_session_id = ?", session.getActivitySessionId());
            }
        }
        var findings = giftViolations(); assertThat(findings.stream().map(row -> row.get("violation"))).contains(expected);
        if (kind.equals("SESSION")) {
            var row = findings.stream().filter(value -> expected.equals(value.get("violation"))).findFirst().orElseThrow();
            assertThat(((Number) row.get("actual_value")).longValue()).isEqualTo(10);
            assertThat(((Number) row.get("expected_value")).longValue()).isEqualTo(9);
        }
        evidence("day12-capacity-inspection-" + kind, Map.of("expectedViolation", expected, "findings", findings));
    }

    @Test void plannedZoneCapacityMayBeBelowTheSessionLimitAndAllocationDoesNotChangeIt() throws Exception {
        jdbc.update("UPDATE activity_session SET capacity = 11 WHERE activity_session_id = ?", session.getActivitySessionId());
        allocate(gift.getZoneId(), "under-capacity", 5, 201); assertThat(giftViolations()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT SUM(total_amount) FROM session_zone WHERE session_id = ?", Integer.class, session.getActivitySessionId())).isEqualTo(10);
    }

    @Test void competingForTheLastGiftSeatCommitsExactlyOneNewAllocation() throws Exception {
        allocate(gift.getZoneId(), "first-five", 5, 201);
        var barrier = new CyclicBarrier(2);
        var a = async(() -> { barrier.await(10, TimeUnit.SECONDS); return response(gift.getZoneId(), "last-A", 1); });
        var b = async(() -> { barrier.await(10, TimeUnit.SECONDS); return response(gift.getZoneId(), "last-B", 1); });
        var responses = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        assertThat(responses.stream().map(MockHttpServletResponse::getStatus)).containsExactlyInAnyOrder(201, 409);
        var rejected = responses.stream().filter(value -> value.getStatus() == 409).findFirst().orElseThrow();
        assertThat(objectMapper.readTree(rejected.getContentAsString()).path("code").asText()).isEqualTo("GIFT_INSUFFICIENT_INVENTORY");
        assertThat(zoneMapper.findById(gift.getZoneId()).getRemainsAmount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gift_inventory_operation", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gift_inventory_operation_item", Integer.class)).isEqualTo(6);
        assertThat(giftViolations()).isEmpty();
        evidence("day12-gift-last-seat-race", Map.of("statuses", List.of(201, 409), "remaining", 0, "allocatedSeats", 6, "operations", 2));
    }

    @Test void historySharedZoneLockAndAllocationFollowTheSameParentFirstOrder() throws Exception {
        var first = allocate(gift.getZoneId(), "before-read", 1, 201);
        var held = gate(); var release = gate(); var attempting = gate();
        var readerId = new java.util.concurrent.atomic.AtomicLong(); var writerId = new java.util.concurrent.atomic.AtomicLong();
        var reading = async(() -> {
            var probe = new Probe(); probe.afterZoneShare = () -> { readerId.set(connectionId()); held.countDown(); await(release); };
            observation.current.set(probe);
            try { return history(gift.getZoneId(), ""); } finally { observation.current.remove(); }
        });
        await(held);
        var writing = async(() -> {
            var probe = new Probe(); probe.beforeZone = () -> { writerId.set(connectionId()); attempting.countDown(); };
            observation.current.set(probe);
            try { return allocate(gift.getZoneId(), "after-read", 1, 201); } finally { observation.current.remove(); }
        });
        try {
            await(attempting);
            evidence("day12-gift-history-allocation-lock-wait", awaitDatabaseLockWait(writerId.get(), readerId.get(), "session_zone"));
        } finally { release.countDown(); }
        assertThat(reading.get(15, TimeUnit.SECONDS).allocations()).containsExactly(first); writing.get(15, TimeUnit.SECONDS);
        assertThat(history(gift.getZoneId(), "").allocations()).hasSize(2); assertThat(giftViolations()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"publisherId", "sessionId", "zoneType", "allocationStatus", "remaining", "remainsAmount",
            "zoneId", "totalAmount", "status", "seatIds", "createdAt", "receiver_user_id", "recipient_id", "owner_user_id", "real_name", "id_card", "check_in_status"})
    void clientCanSupplyOnlyRequestIdAndQuantity(String field) throws Exception {
        var before = snapshot();
        for (Object value : Arrays.asList("forged", null)) {
            var body = new LinkedHashMap<String, Object>(Map.of("requestId", "forged", "quantity", 1)); body.put(field, value);
            postJson(path(gift.getZoneId()), publisher, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_GIFT_REQUEST"));
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING_KEY", "NULL_KEY", "BLANK_KEY", "LONG_KEY", "MISSING_QUANTITY", "NULL_QUANTITY", "ZERO", "NEGATIVE", "FRACTION", "STRING", "OVERFLOW"})
    void invalidPayloadsAreRejectedWithoutMutations(String change) throws Exception {
        var body = new LinkedHashMap<String, Object>(Map.of("requestId", "invalid", "quantity", 1));
        switch (change) {
            case "MISSING_KEY" -> body.remove("requestId"); case "NULL_KEY" -> body.put("requestId", null);
            case "BLANK_KEY" -> body.put("requestId", " "); case "LONG_KEY" -> body.put("requestId", "x".repeat(65));
            case "MISSING_QUANTITY" -> body.remove("quantity"); case "NULL_QUANTITY" -> body.put("quantity", null);
            case "ZERO" -> body.put("quantity", 0); case "NEGATIVE" -> body.put("quantity", -1);
            case "FRACTION" -> body.put("quantity", 1.5); case "OVERFLOW" -> body.put("quantity", Long.MAX_VALUE);
            case "STRING" -> body.put("quantity", "1");
            default -> throw new IllegalArgumentException(change);
        }
        var before = snapshot(); postJson(path(gift.getZoneId()), publisher, body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GIFT_REQUEST")); assertThat(snapshot()).isEqualTo(before);
    }

    @Test void onlyTheCurrentActiveOwningPublisherCanAllocate() throws Exception {
        var before = snapshot();
        mvc.perform(post(path(gift.getZoneId())).contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"a\",\"quantity\":1}")).andExpect(status().isUnauthorized());
        postJson(path(gift.getZoneId()), buyer, Map.of("requestId", "buyer", "quantity", 1)).andExpect(status().isForbidden());
        postJson(path(gift.getZoneId()), otherPublisher, Map.of("requestId", "other", "quantity", 1))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GIFT_ACCESS_DENIED"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"DISABLED", "USER"})
    void serviceRechecksPublisherStatusAndRoleAfterAuthentication(String changed) {
        var request = new AllocateGiftSeatsRequest(); request.setRequestId("stale-role"); request.setQuantity(1);
        assertThatThrownBy(() -> asPublisher(() -> {
            jdbc.update("UPDATE app_user SET " + (changed.equals("USER") ? "role" : "status") + " = ? WHERE id = ?", changed, publisher.getId());
            return allocations.allocate(gift.getZoneId(), request);
        })).isInstanceOfSatisfying(GiftException.class, failure -> assertThat(failure.getErrorCode().name())
                .isEqualTo(changed.equals("USER") ? "GIFT_ACCESS_DENIED" : "GIFT_USER_DISABLED"));
    }

    @ParameterizedTest @CsvSource({"ACTIVITY_DRAFT,GIFT_SCOPE_UNAVAILABLE", "ACTIVITY_CANCELLED,GIFT_SCOPE_UNAVAILABLE", "ACTIVITY_FINISHED,GIFT_SCOPE_UNAVAILABLE",
            "SESSION_CANCELLED,GIFT_SCOPE_UNAVAILABLE", "SESSION_FINISHED,GIFT_SCOPE_UNAVAILABLE", "STARTED,GIFT_SCOPE_UNAVAILABLE", "FREE,GIFT_SCOPE_UNAVAILABLE",
            "MISSING_SESSION,GIFT_SCOPE_INVALID", "MISSING_ACTIVITY,GIFT_SCOPE_INVALID"})
    void invalidLifecyclesAndLogicalParentChainsCannotAllocate(String change, String code) throws Exception {
        if (change.startsWith("ACTIVITY_")) { jdbc.update("UPDATE activity SET status = ? WHERE activity_id = ?", change.substring(9), activity.getActivityId()); }
        else if (change.startsWith("SESSION_")) { jdbc.update("UPDATE activity_session SET status = ? WHERE activity_session_id = ?", change.substring(8), session.getActivitySessionId()); }
        else if (change.equals("STARTED")) { jdbc.update("UPDATE activity_session SET start_time = ?, end_time = ? WHERE activity_session_id = ?", now().minusHours(1), now().plusHours(1), session.getActivitySessionId()); }
        else if (change.equals("FREE")) { jdbc.update("UPDATE activity SET type = 'FREE' WHERE activity_id = ?", activity.getActivityId()); }
        else if (change.equals("MISSING_SESSION")) { jdbc.update("UPDATE session_zone SET session_id = ? WHERE zone_id = ?", Long.MAX_VALUE, gift.getZoneId()); }
        else { jdbc.update("UPDATE activity_session SET activity_id = ? WHERE activity_session_id = ?", Long.MAX_VALUE, session.getActivitySessionId()); }
        var before = snapshot(); reject(gift.getZoneId(), "lifecycle", 1, 409, code); assertThat(snapshot()).isEqualTo(before);
    }

    @Test void noPurchaserWhitelistOrSalesWindowIsRequiredForThePublishersGiftResources() throws Exception {
        jdbc.update("UPDATE activity SET close_time = ? WHERE activity_id = ?", now().minusHours(1), activity.getActivityId());
        jdbc.update("INSERT INTO whitelist_config(scope_type, scope_id) VALUES ('SESSION', ?)", session.getActivitySessionId());
        allocate(gift.getZoneId(), "after-sales-close", 1, 201);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_session_purchase", Integer.class)).isZero();
    }

    @Test void missingAndPaidZonesAndMalformedPathsAreRejected() throws Exception {
        reject(Long.MAX_VALUE, "missing", 1, 404, "GIFT_ZONE_NOT_FOUND"); reject(paid.getZoneId(), "paid", 1, 409, "GIFT_ZONE_NOT_GIFT");
        for (String id : List.of("0", "-1", "abc")) {
            postJson("/publisher/gift-zones/" + id + "/allocations", publisher, Map.of("requestId", "invalid", "quantity", 1))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_GIFT_REQUEST"));
        }
    }

    @Test void stockWithTooFewAvailableSeatsIsReportedAsAResourceConflict() throws Exception {
        deliberatelyCorrupt = true;
        jdbc.update("UPDATE session_seat SET allocation_status = 'GIFT_ALLOCATED' WHERE seat_id = ?", giftSeats.get(0).getSeatId());
        var before = snapshot(); reject(gift.getZoneId(), "broken-stock", 6, 409, "GIFT_RESOURCE_CONFLICT"); assertThat(snapshot()).isEqualTo(before);
    }

    @Test void mapperGuardsRejectPaidSeatsAndPaidStockEvenWhenCalledDirectly() {
        var before = snapshot();
        tx(() -> {
            zonesLock(paid.getZoneId());
            assertThat(inventory.findAvailableSeats(paid.getZoneId(), 1)).isEmpty();
            assertThat(inventory.occupySeat(seatMapper.findByZoneId(paid.getZoneId()).get(0).getSeatId(), paid.getZoneId())).isZero();
            assertThat(inventory.decreaseStock(paid.getZoneId(), session.getActivitySessionId(), 1)).isZero(); return true;
        });
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @CsvSource({"occupySeat,1", "occupySeat,4", "decreaseStock,1", "insert,1", "insertItem,1", "insertItem,4"})
    void everyPartialResourceOrHistoryWriteRollsBackAndTheSameKeyCanRetry(String method, int occurrence) throws Exception {
        var before = snapshot(); var probe = new Probe(); probe.point = method; probe.occurrence = occurrence; observation.current.set(probe);
        try { reject(gift.getZoneId(), "fault", 4, 500, "GIFT_TECHNICAL_FAILURE"); } finally { observation.current.remove(); }
        assertThat(probe.injected).isTrue(); assertThat(snapshot()).isEqualTo(before); assertThat(giftViolations()).isEmpty();
        allocate(gift.getZoneId(), "fault", 4, 201);
        evidence("day12-gift-rollback-" + method + "-" + occurrence, Map.of("stateUnchangedAfterRollback", true, "retrySucceeded", true));
    }

    @ParameterizedTest @CsvSource({"A,occupySeat,2,2,6,0", "B,insertItem,1,5,1,1"})
    void specifiedGiftFailureExperimentsLeaveAllSeatsAvailableAndNoOperation(String experiment, String method,
            int occurrence, int allocatedBeforeThrow, int remainingBeforeThrow, int historyBeforeThrow) throws Exception {
        var before = snapshot(); var observedInsideTransaction = new LinkedHashMap<String, Object>();
        var probe = new Probe(); probe.point = method; probe.occurrence = occurrence;
        probe.atFailure = () -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionName()).endsWith("GiftAllocationTransactionService.allocate");
            int allocated = jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE zone_id = ? AND allocation_status = 'GIFT_ALLOCATED'", Integer.class, gift.getZoneId());
            int remaining = zoneMapper.findById(gift.getZoneId()).getRemainsAmount();
            int operations = jdbc.queryForObject("SELECT COUNT(*) FROM gift_inventory_operation", Integer.class);
            int items = jdbc.queryForObject("SELECT COUNT(*) FROM gift_inventory_operation_item", Integer.class);
            assertThat(allocated).isEqualTo(allocatedBeforeThrow); assertThat(remaining).isEqualTo(remainingBeforeThrow);
            assertThat(operations).isEqualTo(historyBeforeThrow); assertThat(items).isEqualTo(historyBeforeThrow);
            observedInsideTransaction.putAll(Map.of("allocatedSeats", allocated, "remaining", remaining,
                    "operations", operations, "items", items, "sameTransactionConnection", connectionId()));
        };
        observation.current.set(probe);
        try { reject(gift.getZoneId(), "specified-failure-" + experiment, 5, 500, "GIFT_TECHNICAL_FAILURE"); }
        finally { observation.current.remove(); }
        assertThat(probe.injected).isTrue(); assertThat(observedInsideTransaction).isNotEmpty();
        assertThat(snapshot()).isEqualTo(before);
        assertThat(seatMapper.findByZoneId(gift.getZoneId())).allSatisfy(seat -> assertThat(seat.getAllocationStatus()).isEqualTo("AVAILABLE"));
        assertThat(zoneMapper.findById(gift.getZoneId()).getRemainsAmount()).isEqualTo(6);
        assertThat(zoneMapper.findById(gift.getZoneId()).getTotalAmount()).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gift_inventory_operation", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gift_inventory_operation_item", Integer.class)).isZero();
        assertThat(giftViolations()).isEmpty(); RefundInvariantAssertions.assertConsistent(jdbc);
        evidence("day12-gift-specified-failure-" + experiment, Map.of("requestedQuantity", 5,
                "observedBeforeThrow", observedInsideTransaction, "resourceAndHistorySnapshotUnchanged", true,
                "finalSeatStatus", "AVAILABLE", "finalRemaining", 6, "finalOperations", 0, "finalItems", 0, "reconciliationViolations", 0));
    }

    @ParameterizedTest @ValueSource(strings = {"occupySeat", "decreaseStock", "insert", "insertItem"})
    void everyConditionalWriteRequiresOneAffectedRow(String method) throws Exception {
        var before = snapshot(); var probe = new Probe(); probe.point = method; probe.returnZero = true; observation.current.set(probe);
        try { reject(gift.getZoneId(), "zero", 2, 409, "GIFT_RESOURCE_CONFLICT"); } finally { observation.current.remove(); }
        assertThat(probe.injected).isTrue(); assertThat(snapshot()).isEqualTo(before);
    }

    @Test void zoneLockWaitCannotCarryANewAllocationPastSessionStart() throws Exception {
        var start = now().plusSeconds(3);
        jdbc.update("UPDATE activity_session SET start_time = ?, end_time = ? WHERE activity_session_id = ?", start, start.plusHours(2), session.getActivitySessionId());
        var before = snapshot(); var held = gate(); var release = gate(); var attempting = gate();
        var holderId = new java.util.concurrent.atomic.AtomicLong(); var waiterId = new java.util.concurrent.atomic.AtomicLong();
        var holder = async(() -> tx(() -> {
            zonesLock(gift.getZoneId()); holderId.set(connectionId()); held.countDown(); await(release); return true;
        }));
        await(held);
        var waiting = async(() -> {
            var probe = new Probe(); probe.beforeZone = () -> { waiterId.set(connectionId()); attempting.countDown(); };
            observation.current.set(probe);
            try { return response(gift.getZoneId(), "wait-start", 1); } finally { observation.current.remove(); }
        });
        try {
            await(attempting);
            var wait = awaitDatabaseLockWait(waiterId.get(), holderId.get(), "session_zone");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            while (now().isBefore(start)) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
            evidence("day12-gift-zone-lock-start-boundary", wait);
        } finally { release.countDown(); }
        holder.get(15, TimeUnit.SECONDS); var response = waiting.get(15, TimeUnit.SECONDS);
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(response.getContentAsString()).path("code").asText()).isEqualTo("GIFT_SCOPE_UNAVAILABLE");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void finalTimeCheckRollsBackSeatsStockAndCompletedHistoryTogether() throws Exception {
        var before = snapshot(); var probe = new Probe();
        probe.expireAt = jdbc.queryForObject("SELECT start_time FROM activity_session WHERE activity_session_id = ?", LocalDateTime.class, session.getActivitySessionId());
        observation.current.set(probe);
        try { reject(gift.getZoneId(), "late-final", 2, 409, "GIFT_SCOPE_UNAVAILABLE"); } finally { observation.current.remove(); }
        assertThat(probe.timeReads).isEqualTo(2); assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void concurrentSameZoneRequestsNeverAllocateTwiceOrOversell(boolean sameKey) throws Exception {
        var barrier = new CyclicBarrier(2);
        var a = async(() -> { barrier.await(10, TimeUnit.SECONDS); return response(gift.getZoneId(), "race", 4); });
        var b = async(() -> { barrier.await(10, TimeUnit.SECONDS); return response(gift.getZoneId(), sameKey ? "race" : "other-race", 4); });
        var responses = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        assertThat(responses.stream().map(MockHttpServletResponse::getStatus)).containsExactlyInAnyOrder(201, sameKey ? 200 : 409);
        if (sameKey) { assertThat(objectMapper.readTree(responses.get(0).getContentAsString())).isEqualTo(objectMapper.readTree(responses.get(1).getContentAsString())); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gift_inventory_operation", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gift_inventory_operation_item", Integer.class)).isEqualTo(4);
        assertThat(zoneMapper.findById(gift.getZoneId()).getRemainsAmount()).isEqualTo(2); assertThat(giftViolations()).isEmpty();
        evidence("day12-gift-concurrent-" + (sameKey ? "same" : "different") + "-key", Map.of("statuses", responses.stream().map(MockHttpServletResponse::getStatus).toList(), "allocated", 4, "remaining", 2));
    }

    @Test void crossZoneKeyCompetitionRollsBackTheLoserBeforeFreshTransactionReplay() throws Exception {
        var barrier = new CyclicBarrier(2); var p = new Probe(); var q = new Probe(); p.keyRace = barrier; q.keyRace = barrier;
        p.zoneId = gift.getZoneId(); q.zoneId = otherGift.getZoneId();
        var a = async(() -> observedResponse(p, "cross-zone", 2)); var b = async(() -> observedResponse(q, "cross-zone", 2));
        var responses = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        assertThat(responses.stream().map(MockHttpServletResponse::getStatus)).containsExactlyInAnyOrder(201, 409);
        assertThat(p.replayed || q.replayed).isTrue();
        var loser = responses.stream().filter(r -> r.getStatus() == 409).findFirst().orElseThrow();
        assertThat(objectMapper.readTree(loser.getContentAsString()).path("code").asText()).isEqualTo("GIFT_IDEMPOTENCY_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gift_inventory_operation", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gift_inventory_operation_item", Integer.class)).isEqualTo(2);
        assertThat(giftViolations()).isEmpty();
        evidence("day12-gift-cross-zone-key-race", Map.of("statuses", List.of(201, 409), "rolledBackBeforeReplay", true, "committedOperations", 1, "allocatedSeats", 2));
    }

    @ParameterizedTest @ValueSource(strings = {"SELECT_SEAT", "AUTO_ASSIGN"})
    void giftZonesAreRejectedAtPublicOrderCreationAndAgainAtPayment(String mode) throws Exception {
        jdbc.update("UPDATE activity_session SET seat_mode = ? WHERE activity_session_id = ?", mode, session.getActivitySessionId());
        var before = snapshot();
        var giftItem = orderItem(mode, gift.getZoneId(), giftSeats.get(0).getSeatId(), 1);
        postJson("/ticket-orders", buyer, orderBody(giftItem)).andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("GIFT_ZONE_NOT_PURCHASABLE"));
        assertThat(snapshot()).isEqualTo(before);
        Long order = createPaidOrder(mode, 1);
        jdbc.update("UPDATE ticket_order_item SET zone_id = ?, requested_seat_id = ? WHERE order_id = ?", gift.getZoneId(), mode.equals("SELECT_SEAT") ? giftSeats.get(0).getSeatId() : null, order);
        before = snapshot();
        mvc.perform(post("/ticket-orders/{id}/pay", order).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("GIFT_ZONE_NOT_PURCHASABLE"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void publicSelectedSeatCannotReferToAGiftSeatEvenWithAPaidZoneId() throws Exception {
        var before = snapshot();
        postJson("/ticket-orders", buyer, orderBody(orderItem("SELECT_SEAT", paid.getZoneId(), giftSeats.get(0).getSeatId(), 1)))
                .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("INVALID_SEAT_FOR_ZONE"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void autoAssignmentOnlyUsesThePaidZoneAndConcurrentGiftAllocationStaysIndependent() throws Exception {
        jdbc.update("UPDATE activity_session SET seat_mode = 'AUTO_ASSIGN' WHERE activity_session_id = ?", session.getActivitySessionId());
        // A mistakenly global candidate query would prefer GIFT seats over these PAID seats.
        jdbc.update("UPDATE session_seat SET allocation_priority = 100 WHERE zone_id = ?", paid.getZoneId());
        Long order = createPaidOrder("AUTO_ASSIGN", 2); var barrier = new CyclicBarrier(2);
        var giftTask = async(() -> { barrier.await(10, TimeUnit.SECONDS); return allocate(gift.getZoneId(), "parallel", 3, 201); });
        var paidTask = async(() -> { barrier.await(10, TimeUnit.SECONDS); return mvc.perform(post("/ticket-orders/{id}/pay", order)
                .header(HttpHeaders.AUTHORIZATION, bearer(buyer))).andExpect(status().isOk()).andReturn(); });
        var result = giftTask.get(15, TimeUnit.SECONDS); paidTask.get(15, TimeUnit.SECONDS);
        var paidSeats = seatMapper.findByZoneId(paid.getZoneId()).stream().map(SessionSeat::getSeatId).toList();
        assertThat(jdbc.queryForList("SELECT seat_id FROM ticket", Long.class)).containsExactlyInAnyOrderElementsOf(paidSeats);
        assertThat(result.seatIds()).doesNotContainAnyElementsOf(paidSeats);
        assertThat(zoneMapper.findById(paid.getZoneId()).getRemainsAmount()).isZero();
        assertThat(zoneMapper.findById(gift.getZoneId()).getRemainsAmount()).isEqualTo(3);
        assertThat(purchases.findValidCount(buyer.getId(), session.getActivitySessionId())).isEqualTo(2);
        assertThat(purchases.findValidCount(publisher.getId(), session.getActivitySessionId())).isZero();
        evidence("day12-at06-paid-gift-isolation", Map.of("giftAllocation", result, "paidSeatIds", paidSeats, "paidRemaining", 0, "giftRemaining", 3));
    }

    private Long createPaidOrder(String mode, int quantity) throws Exception {
        var selected = seatMapper.findByZoneId(paid.getZoneId()).get(0).getSeatId();
        var response = postJson("/ticket-orders", buyer, orderBody(orderItem(mode, paid.getZoneId(), selected, quantity)))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return objectMapper.readTree(response.getContentAsString()).path("orderId").asLong();
    }
    private Map<String, Object> orderItem(String mode, Long zoneId, Long seatId, int quantity) {
        var item = new LinkedHashMap<String, Object>(Map.of("zoneId", zoneId, "allocationMode", mode, "quantity", quantity, "acceptNonAdjacent", mode.equals("AUTO_ASSIGN")));
        if (mode.equals("SELECT_SEAT")) { item.put("requestedSeatId", seatId); } return item;
    }
    private Map<String, Object> orderBody(Map<String, Object> item) { return Map.of("requestId", UUID.randomUUID().toString(), "sessionId", session.getActivitySessionId(), "items", List.of(item)); }
    private MockHttpServletResponse observedResponse(Probe probe, String key, int quantity) throws Exception {
        observation.current.set(probe); try { return response(probe.zoneId, key, quantity); } finally { observation.current.remove(); }
    }
    private GiftAllocationResponse allocate(Long zoneId, String key, int quantity, int expected) throws Exception {
        var response = response(zoneId, key, quantity); assertThat(response.getStatus()).isEqualTo(expected);
        return objectMapper.readValue(response.getContentAsString(), GiftAllocationResponse.class);
    }
    private MockHttpServletResponse response(Long zoneId, String key, int quantity) throws Exception {
        return postJson(path(zoneId), publisher, Map.of("requestId", key, "quantity", quantity)).andReturn().getResponse();
    }
    private void reject(Long zoneId, String key, int quantity, int expected, String code) throws Exception {
        var response = response(zoneId, key, quantity); assertThat(response.getStatus()).isEqualTo(expected);
        assertThat(objectMapper.readTree(response.getContentAsString()).path("code").asText()).isEqualTo(code);
    }
    private static String path(Long zoneId) { return "/publisher/gift-zones/" + zoneId + "/allocations"; }
    private GiftAllocationPage history(Long zoneId, String query) throws Exception {
        var response = mvc.perform(get(path(zoneId) + query).header(HttpHeaders.AUTHORIZATION, bearer(publisher)))
                .andExpect(status().isOk()).andReturn().getResponse();
        return objectMapper.readValue(response.getContentAsString(), GiftAllocationPage.class);
    }
    private GiftAllocationResponse detail(Long operationId) throws Exception {
        var response = mvc.perform(get("/publisher/gift-allocations/{id}", operationId).header(HttpHeaders.AUTHORIZATION, bearer(publisher)))
                .andExpect(status().isOk()).andReturn().getResponse();
        return objectMapper.readValue(response.getContentAsString(), GiftAllocationResponse.class);
    }
    private LocalDateTime now() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class); }
    private void zonesLock(Long zoneId) { zoneMapper.findByIdForUpdate(zoneId); }
    private List<List<Map<String, Object>>> snapshot() {
        return List.of("session_zone", "session_seat", "ticket_order", "ticket_order_item", "ticket", "user_session_purchase", "gift_inventory_operation", "gift_inventory_operation_item")
                .stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1")).toList();
    }
    private List<List<Map<String, Object>>> paidSnapshot() {
        return List.of(jdbc.queryForList("SELECT * FROM session_zone WHERE zone_type = 'PAID' ORDER BY zone_id"),
                jdbc.queryForList("SELECT s.* FROM session_seat s JOIN session_zone z ON z.zone_id = s.zone_id WHERE z.zone_type = 'PAID' ORDER BY s.seat_id"),
                jdbc.queryForList("SELECT * FROM ticket"), jdbc.queryForList("SELECT * FROM ticket_order"), jdbc.queryForList("SELECT * FROM user_session_purchase"));
    }
    private List<Map<String, Object>> giftViolations() throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var sql = Files.readString(Path.of("tests", "sql", "gift_invariants.sql"), StandardCharsets.UTF_8).lines()
                .filter(line -> !line.stripLeading().startsWith("--")).collect(java.util.stream.Collectors.joining("\n"));
        var violations = new ArrayList<Map<String, Object>>();
        for (String statement : sql.split(";")) { if (!statement.isBlank()) { violations.addAll(jdbc.queryForList(statement)); } }
        return violations;
    }
    static class Probe {
        String point; int occurrence = 1, seen; boolean returnZero, injected, raced, replayed;
        Long zoneId; CyclicBarrier keyRace; final List<Long> occupied = new ArrayList<>();
        Runnable beforeZone = () -> { }; LocalDateTime expireAt; int timeReads;
        Runnable afterZoneShare = () -> { };
        Runnable atFailure = () -> { };
    }
    @Intercepts({@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
            @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})})
    static class GiftObservation implements Interceptor {
        final ThreadLocal<Probe> current = new ThreadLocal<>();
        @Autowired private JdbcTemplate jdbc;
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var probe = current.get(); var id = ((MappedStatement) invocation.getArgs()[0]).getId();
            if (probe != null && id.endsWith("SessionZoneMapper.findByIdForUpdate")) { probe.beforeZone.run(); }
            Object result = invocation.proceed();
            if (probe != null) {
                if (id.endsWith("SessionZoneMapper.findByIdForShare")) { probe.afterZoneShare.run(); }
                if (probe.expireAt != null && id.endsWith("DatabaseTimeMapper.currentTime") && ++probe.timeReads == 2) { return List.of(probe.expireAt); }
                if (id.endsWith("GiftOperationMapper.findByRequest") && probe.keyRace != null) {
                    if (((List<?>) result).isEmpty() && !probe.raced) { probe.raced = true; probe.keyRace.await(10, TimeUnit.SECONDS); }
                    if (String.valueOf(TransactionSynchronizationManager.getCurrentTransactionName()).endsWith(".replay")) {
                        probe.replayed = true;
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE zone_id = ? AND allocation_status = 'GIFT_ALLOCATED'", Integer.class, probe.zoneId)).isZero();
                    }
                }
                if (id.endsWith("GiftInventoryMapper.occupySeat")) { probe.occupied.add((Long) ((Map<?, ?>) invocation.getArgs()[1]).get("seatId")); }
                if ((id.contains("GiftInventoryMapper.") || id.contains("GiftOperationMapper.")) && id.endsWith("." + probe.point) && ++probe.seen == probe.occurrence) {
                    assertThat(result).isEqualTo(1); probe.injected = true;
                    probe.atFailure.run();
                    if (probe.returnZero) { return 0; }
                    throw new IllegalStateException("Injected GIFT SQL write failure");
                }
            }
            return result;
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration { @Bean GiftObservation giftObservation() { return new GiftObservation(); } }
}
