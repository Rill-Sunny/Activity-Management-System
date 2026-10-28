package com.limitactivitysystem.activity.controller;

import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.mapper.ActivitySessionMapper;
import com.limitactivitysystem.activity.mapper.SessionSeatMapper;
import com.limitactivitysystem.activity.mapper.SessionZoneMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.activity.model.SessionSeat;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.JwtTestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Real Security, MVC, validators and Spring JDBC transaction management; persistence is mocked.
@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
class PublisherActivityPublishTest {

    private static final String ENDPOINT = "/publisher/activities/10/publish";

    @Autowired private MockMvc mvc;
    @Autowired private JwtEncoder jwtEncoder;
    @MockitoBean private AppUserMapper userMapper;
    @MockitoBean private ActivityMapper activityMapper;
    @MockitoBean private ActivitySessionMapper sessionMapper;
    @MockitoBean private SessionZoneMapper zoneMapper;
    @MockitoBean private SessionSeatMapper seatMapper;
    @MockitoBean private DataSource dataSource;

    private Connection connection;
    private Activity activity;
    private AppUser publisher;
    private List<ActivitySession> sessions;
    private Map<Long, List<SessionZone>> zonesBySession;
    private Map<Long, Long> seatCounts;

    @BeforeEach
    void setUp() throws Exception {
        connection = mock(Connection.class);
        given(dataSource.getConnection()).willReturn(connection);
        given(connection.getAutoCommit()).willReturn(true);
        given(connection.getTransactionIsolation()).willReturn(Connection.TRANSACTION_REPEATABLE_READ);

        publisher = new AppUser();
        publisher.setId(2L);
        publisher.setRole("PUBLISHER");
        given(userMapper.findById(2L)).willReturn(publisher);
        AppUser buyer = new AppUser();
        buyer.setId(1L);
        given(userMapper.findById(1L)).willReturn(buyer);

        activity = new Activity();
        activity.setActivityId(10L);
        activity.setPublisherId(2L);
        activity.setType("FREE");
        activity.setOpenTime(LocalDateTime.of(2026, 11, 1, 9, 0));
        activity.setCloseTime(LocalDateTime.of(2026, 11, 10, 18, 0));
        sessions = new ArrayList<>(List.of(validSession(100L, activity.getCloseTime().plusDays(1))));
        zonesBySession = new HashMap<>();
        seatCounts = new HashMap<>();
        given(activityMapper.findByIdForUpdate(10L)).willAnswer(invocation -> {
            assertPublishingTransaction();
            return activity;
        });
        given(sessionMapper.findByActivityId(10L)).willAnswer(invocation -> {
            assertPublishingTransaction();
            return sessions;
        });
        given(zoneMapper.findBySessionId(anyLong())).willAnswer(invocation -> {
            assertPublishingTransaction();
            return zonesBySession.getOrDefault(invocation.getArgument(0), List.of());
        });
        given(seatMapper.countByZoneId(anyLong())).willAnswer(invocation -> {
            assertPublishingTransaction();
            return seatCounts.getOrDefault(invocation.getArgument(0), 0L);
        });
        given(activityMapper.updateStatus(10L, "PUBLISHED")).willAnswer(invocation -> {
            assertPublishingTransaction();
            return 1;
        });
    }

    @AfterEach
    void transactionContextIsCleanedUp() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isNull();
        verify(zoneMapper, never()).insert(any(SessionZone.class));
        verify(seatMapper, never()).insert(any(SessionSeat.class));
        verify(seatMapper, never()).batchInsert(anyList());
    }

    @Test
    void publishesFreeActivityOnlyAfterReadingAndValidatingItsSessions() throws Exception {
        performPublish(204);
        InOrder order = inOrder(connection, activityMapper, sessionMapper);
        order.verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        order.verify(connection).setAutoCommit(false);
        order.verify(activityMapper).findByIdForUpdate(10L);
        order.verify(sessionMapper).findByActivityId(10L);
        order.verify(activityMapper).updateStatus(10L, "PUBLISHED");
        order.verify(connection).commit();
        order.verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        verify(connection, never()).rollback();
        verify(activityMapper, never()).findById(anyLong());
        verifyNoInteractions(zoneMapper, seatMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT_SEAT", "AUTO_ASSIGN"})
    void publishesPaidActivityWithValidPurchaseLimitAndSeatMode(String mode) throws Exception {
        activity.setType("PAID");
        sessions.get(0).setMaxTicketsPerUser(2);
        sessions.get(0).setSeatMode(mode);
        addZone(100L, 200L, "PAID", 70, 70);
        addZone(100L, 201L, "GIFT", 30, 30);
        performPublish(204);
        InOrder order = inOrder(activityMapper, sessionMapper, zoneMapper, seatMapper, connection);
        order.verify(activityMapper).findByIdForUpdate(10L);
        order.verify(sessionMapper).findByActivityId(10L);
        order.verify(zoneMapper).findBySessionId(100L);
        order.verify(seatMapper).countByZoneId(200L);
        order.verify(seatMapper).countByZoneId(201L);
        order.verify(activityMapper).updateStatus(10L, "PUBLISHED");
        order.verify(connection).commit();
    }

    @Test
    void paidZonesMayLeaveSomeSessionCapacityUnplanned() throws Exception {
        configurePaidSession(sessions.get(0));
        addZone(100L, 200L, "PAID", 60, 60);
        addZone(100L, 201L, "GIFT", 30, 30);
        performPublish(204);
        verify(activityMapper).updateStatus(10L, "PUBLISHED");
        verify(connection).commit();
    }

    @Test
    void paidAndGiftCapacitiesAreAddedTogetherBeforePublication() throws Exception {
        configurePaidSession(sessions.get(0));
        SessionZone paid = addZone(100L, 200L, "PAID", 70, 70);
        SessionZone gift = addZone(100L, 201L, "GIFT", 40, 40);
        expectValidationFailure("ZONE_CAPACITY_EXCEEDED",
                "Session 100 PAID and GIFT zone totalAmount (110) must not exceed capacity (100)");
        assertThat(paid.getTotalAmount()).isEqualTo(70);
        assertThat(gift.getTotalAmount()).isEqualTo(40);
        verifyNoInteractions(seatMapper);
    }

    @Test
    void summingLargeZoneCapacitiesMustNotOverflowAnInteger() throws Exception {
        configurePaidSession(sessions.get(0));
        sessions.get(0).setCapacity(Integer.MAX_VALUE);
        addZone(100L, 200L, "PAID", Integer.MAX_VALUE, Integer.MAX_VALUE);
        addZone(100L, 201L, "GIFT", Integer.MAX_VALUE, Integer.MAX_VALUE);
        expectValidationFailure("ZONE_CAPACITY_EXCEEDED", "Session 100 PAID and GIFT zone totalAmount (4294967294)"
                + " must not exceed capacity (2147483647)");
        verifyNoInteractions(seatMapper);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 9, 11})
    void everyZoneMustHaveExactlyItsConfiguredSeatsWithoutChangingItsCapacity(long actualSeats) throws Exception {
        configurePaidSession(sessions.get(0));
        SessionZone zone = addZone(100L, 200L, "PAID", 10, actualSeats);
        expectValidationFailure("SEAT_COUNT_MISMATCH",
                "Zone 200 seat count (" + actualSeats + ") must equal totalAmount (10)");
        assertThat(zone.getTotalAmount()).isEqualTo(10);
        assertThat(zone.getRemainsAmount()).isEqualTo(10);
        assertThat(activity.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void giftZonesAlsoRequireExactlyTheirConfiguredSeats() throws Exception {
        configurePaidSession(sessions.get(0));
        addZone(100L, 200L, "PAID", 70, 70);
        SessionZone gift = addZone(100L, 201L, "GIFT", 30, 29);
        expectValidationFailure("SEAT_COUNT_MISMATCH", "Zone 201 seat count (29) must equal totalAmount (30)");
        verify(seatMapper).countByZoneId(200L);
        verify(seatMapper).countByZoneId(201L);
        assertThat(gift.getTotalAmount()).isEqualTo(30);
    }

    @Test
    void zoneCapacitiesAreCheckedPerSessionRatherThanAcrossTheActivity() throws Exception {
        configurePaidSession(sessions.get(0));
        ActivitySession second = validSession(101L, activity.getCloseTime().plusDays(2));
        configurePaidSession(second);
        sessions.add(second);
        addZone(100L, 200L, "PAID", 100, 100);
        addZone(101L, 201L, "GIFT", 100, 100);
        performPublish(204);
        verify(zoneMapper).findBySessionId(100L);
        verify(zoneMapper).findBySessionId(101L);
        verify(seatMapper).countByZoneId(200L);
        verify(seatMapper).countByZoneId(201L);
        verify(connection).commit();
    }

    @ParameterizedTest
    @ValueSource(strings = {"capacity", "seats"})
    void invalidResourcesInALaterSessionAlsoPreventPublication(String invalidResource) throws Exception {
        configurePaidSession(sessions.get(0));
        ActivitySession second = validSession(101L, activity.getCloseTime().plusDays(2));
        configurePaidSession(second);
        sessions.add(second);
        addZone(100L, 200L, "PAID", 100, 100);
        if ("capacity".equals(invalidResource)) {
            addZone(101L, 201L, "GIFT", 101, 101);
            expectValidationFailure("ZONE_CAPACITY_EXCEEDED",
                    "Session 101 PAID and GIFT zone totalAmount (101) must not exceed capacity (100)");
        } else {
            addZone(101L, 201L, "GIFT", 100, 99);
            expectValidationFailure("SEAT_COUNT_MISMATCH", "Zone 201 seat count (99) must equal totalAmount (100)");
        }
        verify(seatMapper).countByZoneId(200L);
        assertThat(activity.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void closeTimeMayEqualEarliestStartEvenWhenSessionsAreNotSorted() throws Exception {
        sessions.add(validSession(101L, activity.getCloseTime()));
        performPublish(204);
        verify(activityMapper).updateStatus(10L, "PUBLISHED");
    }

    @Test
    void usesMinimumStartTimeRatherThanFirstSession() throws Exception {
        sessions.add(validSession(101L, activity.getCloseTime().minusSeconds(1)));
        expectValidationFailure("INVALID_ACTIVITY_WINDOW",
                "Activity closeTime must not be later than the earliest session startTime");
    }

    @Test
    void activityMustHaveAtLeastOneSession() throws Exception {
        sessions.clear();
        expectValidationFailure("INVALID_SESSION_CONFIG", "Activity must have at least one session before publication");
    }

    @ParameterizedTest
    @ValueSource(strings = {"equal", "reversed", "missingOpen", "missingClose"})
    void invalidActivityWindowIsRejectedBeforeLoadingSessions(String invalidWindow) throws Exception {
        switch (invalidWindow) {
            case "equal" -> activity.setCloseTime(activity.getOpenTime());
            case "reversed" -> activity.setCloseTime(activity.getOpenTime().minusSeconds(1));
            case "missingOpen" -> activity.setOpenTime(null);
            case "missingClose" -> activity.setCloseTime(null);
        }
        expectValidationFailure("INVALID_ACTIVITY_WINDOW", "Activity openTime must be earlier than closeTime");
        verifyNoInteractions(sessionMapper);
    }

    @Test
    void unsupportedActivityTypeCannotBePublished() throws Exception {
        activity.setType("UNKNOWN");
        expectValidationFailure("INVALID_ACTIVITY_TYPE", "Activity type must be FREE or PAID");
        verifyNoInteractions(sessionMapper);
    }

    @ParameterizedTest
    @CsvSource({"PUBLISHED,ACTIVITY_ALREADY_PUBLISHED,Activity is already published",
            "FINISHED,ACTIVITY_NOT_DRAFT,Only DRAFT activities can be published",
            "CANCELLED,ACTIVITY_NOT_DRAFT,Only DRAFT activities can be published"})
    void activityMustStillBeDraft(String state, String code, String detail) throws Exception {
        activity.setStatus(state);
        expectBusinessError(409, code, detail);
        verifyNoInteractions(sessionMapper);
        verifyNoStatusWrite();
    }

    @Test
    void nonexistentActivityReturns404() throws Exception {
        willReturn(null).given(activityMapper).findByIdForUpdate(10L);
        expectBusinessError(404, "ACTIVITY_NOT_FOUND", "Activity not found");
        verifyNoInteractions(sessionMapper);
        verifyNoStatusWrite();
    }

    @Test
    void publisherCannotPublishAnotherPublishersActivity() throws Exception {
        activity.setPublisherId(3L);
        expectBusinessError(403, "NOT_ACTIVITY_OWNER", "Only the activity owner can manage this resource");
        verifyNoInteractions(sessionMapper);
        verifyNoStatusWrite();
    }

    @ParameterizedTest
    @ValueSource(strings = {"equal", "reversed", "missingStart", "missingEnd"})
    void everyPersistedSessionMustHaveAValidTimeWindow(String invalidWindow) throws Exception {
        ActivitySession invalid = sessions.get(0);
        switch (invalidWindow) {
            case "equal" -> invalid.setEndTime(invalid.getStartTime());
            case "reversed" -> invalid.setEndTime(invalid.getStartTime().minusSeconds(1));
            case "missingStart" -> invalid.setStartTime(null);
            case "missingEnd" -> invalid.setEndTime(null);
        }
        expectValidationFailure("INVALID_SESSION_CONFIG", "startTime must be earlier than endTime");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void sessionCapacityMustBePositive(int capacity) throws Exception {
        sessions.get(0).setCapacity(capacity);
        expectValidationFailure("INVALID_CAPACITY", "capacity must be positive");
    }

    @Test
    void missingSessionCapacityIsRejected() throws Exception {
        sessions.get(0).setCapacity(null);
        expectValidationFailure("INVALID_CAPACITY", "capacity must be positive");
    }

    @Test
    void invalidLaterSessionPreventsPublicationEvenIfTheFirstSessionIsValid() throws Exception {
        ActivitySession invalid = validSession(101L, activity.getCloseTime().plusDays(2));
        invalid.setCapacity(0);
        sessions.add(invalid);
        expectValidationFailure("INVALID_CAPACITY", "capacity must be positive");
    }

    @ParameterizedTest
    @CsvSource({"limit", "seatMode", "both"})
    void freeSessionsMustNotContainPaidConfiguration(String field) throws Exception {
        if (!"seatMode".equals(field)) {
            sessions.get(0).setMaxTicketsPerUser(1);
        }
        if (!"limit".equals(field)) {
            sessions.get(0).setSeatMode("SELECT_SEAT");
        }
        expectValidationFailure("INVALID_SESSION_CONFIG", "FREE sessions require null maxTicketsPerUser and seatMode");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101})
    void paidSessionLimitMustBePositiveAndWithinCapacity(int limit) throws Exception {
        activity.setType("PAID");
        sessions.get(0).setSeatMode("SELECT_SEAT");
        sessions.get(0).setMaxTicketsPerUser(limit);
        expectValidationFailure("INVALID_SESSION_CONFIG", "PAID sessions require maxTicketsPerUser between 1 and capacity");
    }

    @Test
    void paidSessionLimitIsRequired() throws Exception {
        activity.setType("PAID");
        sessions.get(0).setSeatMode("SELECT_SEAT");
        expectValidationFailure("INVALID_SESSION_CONFIG", "PAID sessions require maxTicketsPerUser between 1 and capacity");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "INVALID", "null"})
    void paidSessionRequiresAKnownSeatMode(String mode) throws Exception {
        activity.setType("PAID");
        sessions.get(0).setMaxTicketsPerUser(2);
        sessions.get(0).setSeatMode("null".equals(mode) ? null : mode);
        expectValidationFailure("INVALID_SESSION_CONFIG", "PAID sessions require SELECT_SEAT or AUTO_ASSIGN");
    }

    @Test
    void zeroAffectedRowsDoesNotReportSuccessfulPublication() throws Exception {
        willReturn(0).given(activityMapper).updateStatus(10L, "PUBLISHED");
        expectBusinessError(409, "ACTIVITY_PUBLISH_CONFLICT", "Activity state changed; publication failed");
        verify(activityMapper).updateStatus(10L, "PUBLISHED");
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void databaseWriteFailureRollsBack() throws Exception {
        willThrow(new DataAccessResourceFailureException("Publication write failed"))
                .given(activityMapper).updateStatus(10L, "PUBLISHED");
        assertThatThrownBy(() -> performPublish(204))
                .hasRootCauseInstanceOf(DataAccessResourceFailureException.class);
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @ParameterizedTest
    @ValueSource(strings = {"session", "zone", "seats"})
    void publishedActivityRejectsEveryExistingConfigurationWrite(String resource) throws Exception {
        configurePaidSession(sessions.get(0));
        SessionZone zone = addZone(100L, 200L, "PAID", 100, 100);
        given(sessionMapper.findById(100L)).willReturn(sessions.get(0));
        given(zoneMapper.findById(200L)).willReturn(zone);
        willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return activity;
        }).given(activityMapper).findByIdForUpdate(10L);
        willAnswer(invocation -> {
            assertPublishingTransaction();
            activity.setStatus("PUBLISHED");
            return 1;
        }).given(activityMapper).updateStatus(10L, "PUBLISHED");
        performPublish(204);

        String path;
        String body;
        switch (resource) {
            case "session" -> {
                path = "/publisher/activities/10/sessions";
                body = """
                        {"startTime":"2026-11-15T18:00:00","endTime":"2026-11-15T20:00:00",
                         "capacity":100,"maxTicketsPerUser":2,"seatMode":"SELECT_SEAT"}
                        """;
            }
            case "zone" -> {
                path = "/publisher/sessions/100/zones";
                body = """
                        {"name":"New zone","zoneType":"PAID","price":10.00,"totalAmount":1}
                        """;
            }
            default -> {
                path = "/publisher/zones/200/seats";
                body = """
                        {"seats":[{"rowNo":1,"seatNo":101,"allocationPriority":101}]}
                        """;
            }
        }
        mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, authorization(2L, "PUBLISHER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_DRAFT"));

        InOrder order = inOrder(activityMapper, connection);
        order.verify(activityMapper).findByIdForUpdate(10L);
        order.verify(activityMapper).updateStatus(10L, "PUBLISHED");
        order.verify(connection).commit();
        order.verify(activityMapper).findByIdForUpdate(10L);
        order.verify(connection).rollback();
        verify(activityMapper, times(2)).findByIdForUpdate(10L);
        verify(sessionMapper, never()).insert(any(ActivitySession.class));
        assertThat(activity.getStatus()).isEqualTo("PUBLISHED");
        assertThat(zone.getTotalAmount()).isEqualTo(100);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void publishedCoreConfigurationHasNoGenericEditOrDeleteEndpoint(String method) throws Exception {
        activity.setStatus("PUBLISHED");
        String changes = """
                {"type":"FREE","openTime":"2026-11-02T09:00:00","closeTime":"2026-11-09T18:00:00",
                 "startTime":"2026-11-16T18:00:00","endTime":"2026-11-16T20:00:00",
                 "capacity":200,"maxTicketsPerUser":5,"seatMode":"AUTO_ASSIGN",
                 "price":1.00,"totalAmount":200,"rowNo":2,"seatNo":1,"allocationPriority":1}
                """;
        for (String path : List.of("/publisher/activities/10", "/publisher/sessions/100",
                "/publisher/zones/200", "/publisher/zones/200/seats", "/publisher/seats/300", "/sessions/100")) {
            mvc.perform(request(HttpMethod.valueOf(method), path)
                            .header(HttpHeaders.AUTHORIZATION, authorization(2L, "PUBLISHER"))
                            .contentType(MediaType.APPLICATION_JSON).content(changes))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(404, 405));
        }
        verifyNoDatabaseAccess();
    }

    @Test
    void userCannotPublish() throws Exception {
        mvc.perform(post(ENDPOINT).header(HttpHeaders.AUTHORIZATION, authorization(1L, "USER")))
                .andExpect(status().isForbidden())
                .andExpect(content().json("""
                        {"code":"FORBIDDEN","message":"Insufficient permission"}
                        """, JsonCompareMode.STRICT));
        verifyNoDatabaseAccess();
    }

    @Test
    void anonymousCannotPublish() throws Exception {
        mvc.perform(post(ENDPOINT)).andExpect(status().isUnauthorized());
        verifyNoDatabaseAccess();
    }

    @Test
    void disabledPublisherCannotPublishWithOldToken() throws Exception {
        publisher.setStatus("DISABLED");
        performPublish(401);
        verifyNoDatabaseAccess();
    }

    @Test
    void currentUserProviderRechecksActiveStatusAfterAuthentication() throws Exception {
        AppUser disabled = new AppUser();
        disabled.setId(2L);
        disabled.setRole("PUBLISHER");
        disabled.setStatus("DISABLED");
        given(userMapper.findById(2L)).willReturn(publisher, disabled);
        performPublish(401);
        verifyNoInteractions(activityMapper, sessionMapper);
        verify(connection).rollback();
    }

    @Test
    void revokedPublisherRoleIsRejectedInsideService() throws Exception {
        AppUser revoked = new AppUser();
        revoked.setId(2L);
        given(userMapper.findById(2L)).willReturn(publisher, revoked);
        performPublish(403);
        verifyNoInteractions(activityMapper, sessionMapper);
        verify(connection).rollback();
    }

    private void expectValidationFailure(String code, String detail) throws Exception {
        expectBusinessError(400, code, detail);
        verifyNoStatusWrite();
    }

    private void expectBusinessError(int expectedStatus, String code, String detail) throws Exception {
        mvc.perform(post(ENDPOINT).header(HttpHeaders.AUTHORIZATION, authorization(2L, "PUBLISHER")))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.title", not(blankOrNullString())));
    }

    private void verifyNoStatusWrite() throws Exception {
        verify(activityMapper, never()).updateStatus(anyLong(), anyString());
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    private void verifyNoDatabaseAccess() throws Exception {
        verifyNoInteractions(activityMapper, sessionMapper, zoneMapper, seatMapper);
        verify(dataSource, never()).getConnection();
    }

    private void performPublish(int expectedStatus) throws Exception {
        var result = mvc.perform(post(ENDPOINT).header(HttpHeaders.AUTHORIZATION, authorization(2L, "PUBLISHER")))
                .andExpect(status().is(expectedStatus));
        if (expectedStatus == 204) {
            result.andExpect(content().string(""));
        }
    }

    private void assertPublishingTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
    }

    private ActivitySession validSession(Long id, LocalDateTime start) {
        ActivitySession session = new ActivitySession();
        session.setActivitySessionId(id);
        session.setActivityId(10L);
        session.setStartTime(start);
        session.setEndTime(start.plusHours(2));
        session.setCapacity(100);
        return session;
    }

    private void configurePaidSession(ActivitySession session) {
        activity.setType("PAID");
        session.setMaxTicketsPerUser(2);
        session.setSeatMode("SELECT_SEAT");
    }

    private SessionZone addZone(Long sessionId, Long zoneId, String type, int totalAmount, long actualSeats) {
        SessionZone zone = new SessionZone();
        zone.setZoneId(zoneId);
        zone.setSessionId(sessionId);
        zone.setZoneType(type);
        zone.setTotalAmount(totalAmount);
        zone.setRemainsAmount(totalAmount);
        zonesBySession.computeIfAbsent(sessionId, key -> new ArrayList<>()).add(zone);
        seatCounts.put(zoneId, actualSeats);
        return zone;
    }

    private String authorization(long userId, String role) {
        return "Bearer " + JwtTestTokens.token(jwtEncoder, userId, role);
    }
}
