package com.limitactivitysystem.activity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.mapper.ActivitySessionMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.JwtTestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Real Security filters, controllers, service proxy and Spring JDBC transaction manager.
// Only persistence and the JDBC connection are mocked; the test itself has no surrounding transaction.
@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
class PublisherActivitySessionCreationTest {

    private static final String ENDPOINT = "/publisher/activities/10/sessions";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtEncoder jwtEncoder;
    @MockitoBean private AppUserMapper appUserMapper;
    @MockitoBean private ActivityMapper activityMapper;
    @MockitoBean private ActivitySessionMapper sessionMapper;
    @MockitoBean private DataSource dataSource;

    private Connection connection;
    private Activity activity;
    private AppUser publisher;

    @BeforeEach
    void setUp() throws Exception {
        connection = mock(Connection.class);
        given(dataSource.getConnection()).willReturn(connection);
        given(connection.getAutoCommit()).willReturn(true);

        publisher = new AppUser();
        publisher.setId(2L);
        publisher.setRole("PUBLISHER");
        given(appUserMapper.findById(2L)).willReturn(publisher);
        AppUser buyer = new AppUser();
        buyer.setId(1L);
        given(appUserMapper.findById(1L)).willReturn(buyer);

        activity = new Activity();
        activity.setActivityId(10L);
        activity.setPublisherId(2L);
        activity.setType("PAID");
        given(activityMapper.findByIdForUpdate(10L)).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return activity;
        });
        given(sessionMapper.insert(any(ActivitySession.class))).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            ActivitySession session = invocation.getArgument(0);
            assertThat(session.getActivitySessionId()).isNull();
            session.setActivitySessionId(100L);
            return 1;
        });
    }

    @AfterEach
    void transactionIsClosed() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT_SEAT", "AUTO_ASSIGN"})
    void createsPaidSessionUnderParentLockAndCommitsAfterInsert(String seatMode) throws Exception {
        Map<String, Object> request = validRequest();
        request.put("seatMode", seatMode);
        performCreate(request, 201);

        ArgumentCaptor<ActivitySession> saved = ArgumentCaptor.forClass(ActivitySession.class);
        InOrder order = inOrder(connection, activityMapper, sessionMapper);
        order.verify(connection).setAutoCommit(false);
        order.verify(activityMapper).findByIdForUpdate(10L);
        order.verify(sessionMapper).insert(saved.capture());
        order.verify(connection).commit();
        verify(connection, never()).rollback();
        ActivitySession session = saved.getValue();
        assertThat(session.getActivityId()).isEqualTo(10L);
        assertThat(session.getStatus()).isEqualTo("SCHEDULED");
        assertThat(session.getStartTime()).isEqualTo(LocalDateTime.of(2026, 11, 15, 18, 0));
        assertThat(session.getEndTime()).isEqualTo(LocalDateTime.of(2026, 11, 15, 21, 0));
        assertThat(session.getCapacity()).isEqualTo(100);
        assertThat(session.getMaxTicketsPerUser()).isEqualTo(2);
        assertThat(session.getSeatMode()).isEqualTo(seatMode);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void freeSessionAcceptsOnlyNullOrOmittedPaidConfiguration(boolean explicitNulls) throws Exception {
        activity.setType("FREE");
        Map<String, Object> request = validRequest();
        if (explicitNulls) {
            request.put("maxTicketsPerUser", null);
            request.put("seatMode", null);
        } else {
            request.remove("maxTicketsPerUser");
            request.remove("seatMode");
        }
        performCreate(request, 201);
        ArgumentCaptor<ActivitySession> saved = ArgumentCaptor.forClass(ActivitySession.class);
        verify(sessionMapper).insert(saved.capture());
        assertThat(saved.getValue().getMaxTicketsPerUser()).isNull();
        assertThat(saved.getValue().getSeatMode()).isNull();
        verify(connection).commit();
    }

    @Test
    void paidLimitMayEqualCapacity() throws Exception {
        Map<String, Object> request = validRequest();
        request.put("maxTicketsPerUser", 100);
        performCreate(request, 201);
    }

    @ParameterizedTest
    @MethodSource("invalidTypeConfigurations")
    void rejectsConfigurationInconsistentWithParentType(String type, Integer limit, String seatMode) throws Exception {
        activity.setType(type);
        Map<String, Object> request = validRequest();
        request.put("maxTicketsPerUser", limit);
        request.put("seatMode", seatMode);
        performCreate(request, 400).andExpect(jsonPath("$.code").value("INVALID_SESSION_CONFIG"));
        verifyNoInteractions(sessionMapper);
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    static Stream<Arguments> invalidTypeConfigurations() {
        return Stream.of(
                Arguments.of("PAID", null, "SELECT_SEAT"),
                Arguments.of("PAID", 0, "SELECT_SEAT"),
                Arguments.of("PAID", -1, "SELECT_SEAT"),
                Arguments.of("PAID", 101, "SELECT_SEAT"),
                Arguments.of("PAID", 2, null),
                Arguments.of("PAID", 2, "INVALID"),
                Arguments.of("PAID", 2, ""),
                Arguments.of("FREE", 1, null),
                Arguments.of("FREE", 0, null),
                Arguments.of("FREE", null, "AUTO_ASSIGN"),
                Arguments.of("FREE", null, ""),
                Arguments.of("FREE", 2, "SELECT_SEAT"));
    }

    @Test
    void missingActivityReturns404() throws Exception {
        mvc.perform(post("/publisher/activities/999/sessions")
                        .header(HttpHeaders.AUTHORIZATION, authorization(2L, "PUBLISHER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"));
        verifyNoInteractions(sessionMapper);
        verify(connection).rollback();
    }

    @Test
    void publisherCannotConfigureAnotherPublishersActivity() throws Exception {
        activity.setPublisherId(3L);
        performCreate(validRequest(), 403).andExpect(jsonPath("$.code").value("NOT_ACTIVITY_OWNER"));
        verifyNoInteractions(sessionMapper);
        verify(connection).rollback();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLISHED", "FINISHED", "CANCELLED"})
    void nonDraftActivityCannotBeConfigured(String state) throws Exception {
        activity.setStatus(state);
        performCreate(validRequest(), 409).andExpect(jsonPath("$.code").value("ACTIVITY_NOT_DRAFT"));
        verifyNoInteractions(sessionMapper);
        verify(connection).rollback();
    }

    @Test
    void userIsRejectedBySecurityBeforeAnyConfigurationAccess() throws Exception {
        mvc.perform(post(ENDPOINT).header(HttpHeaders.AUTHORIZATION, authorization(1L, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isForbidden())
                .andExpect(content().json("""
                        {"code":"FORBIDDEN","message":"Insufficient permission"}
                        """, JsonCompareMode.STRICT));
        verifyNoInteractions(activityMapper, sessionMapper, dataSource);
    }

    @Test
    void anonymousRequestIsRejectedBySecurity() throws Exception {
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(activityMapper, sessionMapper, dataSource);
    }

    @Test
    void disabledPublisherIsRejected() throws Exception {
        publisher.setStatus("DISABLED");
        performCreate(validRequest(), 401);
        verifyNoInteractions(activityMapper, sessionMapper, dataSource);
    }

    @Test
    void currentUserProviderRechecksDisabledStatusInsideService() throws Exception {
        AppUser disabled = new AppUser();
        disabled.setId(2L);
        disabled.setRole("PUBLISHER");
        disabled.setStatus("DISABLED");
        given(appUserMapper.findById(2L)).willReturn(publisher, disabled);
        performCreate(validRequest(), 401);
        verifyNoInteractions(activityMapper, sessionMapper);
        verify(connection).rollback();
    }

    @Test
    void roleRevokedAfterAuthenticationIsRejectedInsideService() throws Exception {
        AppUser revoked = new AppUser();
        revoked.setId(2L);
        given(appUserMapper.findById(2L)).willReturn(publisher, revoked);
        performCreate(validRequest(), 403);
        verifyNoInteractions(activityMapper, sessionMapper);
        verify(connection).rollback();
    }

    @ParameterizedTest
    @ValueSource(strings = {"activityId", "activitySessionId", "status", "publisherId"})
    void requestCannotSupplyServerOwnedFieldsEvenWhenNull(String field) throws Exception {
        Map<String, Object> request = validRequest();
        request.put(field, null);
        performCreate(request, 400);
        verifyNoInteractions(activityMapper, sessionMapper, dataSource);
    }

    @ParameterizedTest
    @ValueSource(strings = {"startTime", "endTime", "capacity"})
    void requiredFieldsCannotBeOmitted(String field) throws Exception {
        Map<String, Object> request = validRequest();
        request.remove(field);
        performCreate(request, 400);
        verifyNoInteractions(activityMapper, sessionMapper, dataSource);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void capacityMustBePositive(int capacity) throws Exception {
        Map<String, Object> request = validRequest();
        request.put("capacity", capacity);
        performCreate(request, 400).andExpect(jsonPath("$.code").value("INVALID_CAPACITY"));
        verifyNoInteractions(activityMapper, sessionMapper, dataSource);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-15T18:00:00", "2026-11-15T17:59:59"})
    void sessionEndMustBeLaterThanStart(String endTime) throws Exception {
        Map<String, Object> request = validRequest();
        request.put("endTime", endTime);
        performCreate(request, 400).andExpect(jsonPath("$.code").value("INVALID_SESSION_CONFIG"));
        verifyNoInteractions(sessionMapper);
        verify(connection).rollback();
    }

    @Test
    void insertFailureRollsBackWithoutCommitting() throws Exception {
        given(sessionMapper.insert(any(ActivitySession.class)))
                .willThrow(new DataIntegrityViolationException("Insert failed"));
        assertThatThrownBy(() -> performCreate(validRequest(), 201))
                .hasRootCauseInstanceOf(DataIntegrityViolationException.class);
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    private ResultActions performCreate(Map<String, Object> request, int expectedStatus) throws Exception {
        var result = mvc.perform(post(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, authorization(2L, "PUBLISHER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is(expectedStatus));
        if (expectedStatus == 201) {
            result.andExpect(content().json(
                    "{\"activitySessionId\":100,\"status\":\"SCHEDULED\"}", JsonCompareMode.STRICT));
        }
        return result;
    }

    private String authorization(long userId, String role) {
        return "Bearer " + JwtTestTokens.token(jwtEncoder, userId, role);
    }

    private Map<String, Object> validRequest() {
        Map<String, Object> request = new HashMap<>();
        request.put("startTime", "2026-11-15T18:00:00");
        request.put("endTime", "2026-11-15T21:00:00");
        request.put("capacity", 100);
        request.put("maxTicketsPerUser", 2);
        request.put("seatMode", "SELECT_SEAT");
        return request;
    }
}
