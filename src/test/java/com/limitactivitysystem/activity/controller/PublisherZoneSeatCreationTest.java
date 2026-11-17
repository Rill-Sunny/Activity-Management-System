package com.limitactivitysystem.activity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Real Security filters and Spring JDBC transaction management; persistence and the connection are mocked.
@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
class PublisherZoneSeatCreationTest {
    @MockitoBean private com.limitactivitysystem.cache.CacheInvalidationRecorder cacheInvalidations;
    @MockitoBean private com.limitactivitysystem.cache.CacheInvalidationDelivery cacheDelivery;

    private static final String ENDPOINT = "/publisher/zones/200/seats";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtEncoder jwtEncoder;
    @MockitoBean private AppUserMapper userMapper;
    @MockitoBean private SessionZoneMapper zoneMapper;
    @MockitoBean private ActivitySessionMapper sessionMapper;
    @MockitoBean private ActivityMapper activityMapper;
    @MockitoBean private SessionSeatMapper seatMapper;
    @MockitoBean private DataSource dataSource;

    private Connection connection;
    private Activity activity;
    private SessionZone zone;
    private AppUser publisher;

    @BeforeEach
    void setUp() throws Exception {
        connection = mock(Connection.class);
        given(dataSource.getConnection()).willReturn(connection);
        given(connection.getAutoCommit()).willReturn(true);

        publisher = new AppUser();
        publisher.setId(2L);
        publisher.setRole("PUBLISHER");
        given(userMapper.findById(2L)).willReturn(publisher);
        AppUser buyer = new AppUser();
        buyer.setId(1L);
        given(userMapper.findById(1L)).willReturn(buyer);

        zone = new SessionZone();
        zone.setZoneId(200L);
        zone.setSessionId(100L);
        zone.setZoneType("PAID");
        zone.setTotalAmount(20);
        given(zoneMapper.findById(200L)).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return zone;
        });
        ActivitySession session = new ActivitySession();
        session.setActivitySessionId(100L);
        session.setActivityId(10L);
        given(sessionMapper.findById(100L)).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return session;
        });
        activity = new Activity();
        activity.setActivityId(10L);
        activity.setPublisherId(2L);
        activity.setType("PAID");
        given(activityMapper.findByIdForUpdate(10L)).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return activity;
        });
        given(seatMapper.batchInsert(anyList())).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            List<SessionSeat> seats = invocation.getArgument(0);
            for (int i = 0; i < seats.size(); i++) {
                assertThat(seats.get(i).getSeatId()).isNull();
                seats.get(i).setSeatId(300L + i);
            }
            return seats.size();
        });
    }

    @AfterEach
    void transactionIsClosed() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "GIFT"})
    void createsWholeBatchUnderTheActivityLock(String zoneType) throws Exception {
        zone.setZoneType(zoneType);
        performCreate(validRequest(), 201);

        ArgumentCaptor<List<SessionSeat>> saved = ArgumentCaptor.forClass(List.class);
        InOrder order = inOrder(connection, zoneMapper, sessionMapper, activityMapper, seatMapper);
        order.verify(connection).setAutoCommit(false);
        order.verify(zoneMapper).findById(200L);
        order.verify(sessionMapper).findById(100L);
        order.verify(activityMapper).findByIdForUpdate(10L);
        order.verify(seatMapper).batchInsert(saved.capture());
        order.verify(connection).commit();
        verify(seatMapper, never()).insert(org.mockito.ArgumentMatchers.any(SessionSeat.class));
        verify(connection, never()).rollback();
        List<SessionSeat> seats = saved.getValue();
        assertThat(seats).hasSize(2).allSatisfy(seat -> {
            assertThat(seat.getZoneId()).isEqualTo(200L);
            assertThat(seat.getAllocationStatus()).isEqualTo("AVAILABLE");
            assertThat(seat.getRowNo()).isEqualTo(1);
        });
        assertThat(seats).extracting(SessionSeat::getSeatNo).containsExactly(1, 2);
        assertThat(seats).extracting(SessionSeat::getAllocationPriority).containsExactly(1, 2);
    }

    @Test
    void sameSeatNumberInDifferentRowsAndEqualPrioritiesAreAllowed() throws Exception {
        Map<String, Object> request = validRequest();
        request.put("seats", List.of(seat(1, 1, 1), seat(2, 1, 1)));
        performCreate(request, 201);
    }

    @Test
    void missingZoneReturns404() throws Exception {
        willReturn(null).given(zoneMapper).findById(200L);
        performCreate(validRequest(), 404).andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));
        verifyNoInteractions(sessionMapper, activityMapper, seatMapper);
        verify(connection).rollback();
    }

    @Test
    void zoneWithMissingParentSessionIsRejected() throws Exception {
        willReturn(null).given(sessionMapper).findById(100L);
        performCreate(validRequest(), 404).andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
        verifyNoInteractions(activityMapper, seatMapper);
        verify(connection).rollback();
    }

    @Test
    void sessionWithMissingParentActivityIsRejected() throws Exception {
        willReturn(null).given(activityMapper).findByIdForUpdate(10L);
        performCreate(validRequest(), 404).andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"));
        verifyNoInteractions(seatMapper);
        verify(connection).rollback();
    }

    @Test
    void existingZoneAndSessionDoNotGrantAccessToAnotherPublishersActivity() throws Exception {
        activity.setPublisherId(3L);
        performCreate(validRequest(), 403).andExpect(jsonPath("$.code").value("NOT_ACTIVITY_OWNER"));
        verifyNoInteractions(seatMapper);
        verify(connection).rollback();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLISHED", "FINISHED", "CANCELLED"})
    void nonDraftActivityRejectsNewSeats(String state) throws Exception {
        activity.setStatus(state);
        performCreate(validRequest(), 409).andExpect(jsonPath("$.code").value("ACTIVITY_NOT_DRAFT"));
        verifyNoInteractions(seatMapper);
        verify(connection).rollback();
    }

    @Test
    void freeActivityCannotHaveZoneSeats() throws Exception {
        activity.setType("FREE");
        performCreate(validRequest(), 400);
        verifyNoInteractions(seatMapper);
        verify(connection).rollback();
    }

    @Test
    void duplicatePositionWithinBatchRejectsEverythingBeforeInsert() throws Exception {
        Map<String, Object> request = validRequest();
        request.put("seats", List.of(seat(1, 1, 1), seat(1, 1, 2)));
        performCreate(request, 400).andExpect(jsonPath("$.code").value("INVALID_SEAT_CONFIG"));
        verifyNoInteractions(seatMapper);
        verify(connection).rollback();
    }

    @Test
    void duplicateExistingSeatRollsBackEntireBatch() throws Exception {
        willThrow(new DuplicateKeyException("duplicate seat position"))
                .given(seatMapper).batchInsert(anyList());
        performCreate(validRequest(), 409).andExpect(jsonPath("$.code").value("SEAT_ALREADY_EXISTS"));
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"seats\":null}", "{\"seats\":[]}", "{\"seats\":[null]}"})
    void batchMustContainNonNullSeats(String body) throws Exception {
        mvc.perform(post(ENDPOINT).header(HttpHeaders.AUTHORIZATION, authorization(2L, "PUBLISHER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoConfigurationAccess();
    }

    @ParameterizedTest
    @CsvSource({"rowNo,0", "rowNo,-1", "seatNo,0", "seatNo,-1", "allocationPriority,0", "allocationPriority,-1"})
    void invalidSeatInBatchRejectsAllSeats(String field, int value) throws Exception {
        Map<String, Object> invalid = seat(1, 2, 2);
        invalid.put(field, value);
        Map<String, Object> request = validRequest();
        request.put("seats", List.of(seat(1, 1, 1), invalid));
        performCreate(request, 400);
        verifyNoConfigurationAccess();
    }

    @ParameterizedTest
    @ValueSource(strings = {"rowNo", "seatNo", "allocationPriority"})
    void seatFieldsAreRequired(String field) throws Exception {
        Map<String, Object> invalid = seat(1, 2, 2);
        invalid.remove(field);
        Map<String, Object> request = validRequest();
        request.put("seats", List.of(seat(1, 1, 1), invalid));
        performCreate(request, 400);
        verifyNoConfigurationAccess();
    }

    @ParameterizedTest
    @CsvSource({"zoneId,false", "seatId,false", "allocationStatus,false",
            "zoneId,true", "seatId,true", "allocationStatus,true"})
    void clientCannotSupplySeatIdentityOrStatus(String field, boolean nullValue) throws Exception {
        Map<String, Object> invalid = seat(1, 2, 2);
        invalid.put(field, nullValue ? null : "allocationStatus".equals(field) ? "SOLD" : 999);
        Map<String, Object> request = validRequest();
        request.put("seats", List.of(seat(1, 1, 1), invalid));
        performCreate(request, 400);
        verifyNoConfigurationAccess();
    }

    @ParameterizedTest
    @ValueSource(strings = {"zoneId", "sessionId", "activityId", "publisherId"})
    void topLevelParentIdsCannotOverrideStoredOwnership(String field) throws Exception {
        Map<String, Object> request = validRequest();
        request.put(field, 999);
        performCreate(request, 400);
        verifyNoConfigurationAccess();
    }

    @Test
    void userCannotCreateSeats() throws Exception {
        mvc.perform(post(ENDPOINT).header(HttpHeaders.AUTHORIZATION, authorization(1L, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isForbidden())
                .andExpect(content().json("""
                        {"code":"FORBIDDEN","message":"Insufficient permission"}
                        """, JsonCompareMode.STRICT));
        verifyNoConfigurationAccess();
    }

    @Test
    void anonymousCannotCreateSeats() throws Exception {
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());
        verifyNoConfigurationAccess();
    }

    @Test
    void disabledPublisherCannotUseOldToken() throws Exception {
        publisher.setStatus("DISABLED");
        performCreate(validRequest(), 401);
        verifyNoConfigurationAccess();
    }

    @Test
    void serviceRechecksActiveStatusAfterAuthentication() throws Exception {
        AppUser disabled = new AppUser();
        disabled.setId(2L);
        disabled.setRole("PUBLISHER");
        disabled.setStatus("DISABLED");
        given(userMapper.findById(2L)).willReturn(publisher, disabled);
        performCreate(validRequest(), 401);
        verifyNoInteractions(zoneMapper, sessionMapper, activityMapper, seatMapper);
        verify(connection).rollback();
    }

    @Test
    void serviceRejectsRoleRevokedAfterAuthentication() throws Exception {
        AppUser revoked = new AppUser();
        revoked.setId(2L);
        given(userMapper.findById(2L)).willReturn(publisher, revoked);
        performCreate(validRequest(), 403);
        verifyNoInteractions(zoneMapper, sessionMapper, activityMapper, seatMapper);
        verify(connection).rollback();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void publishedSeatConfigurationHasNoEditOrDeleteRoute(String method) throws Exception {
        activity.setStatus("PUBLISHED");
        mvc.perform(request(HttpMethod.valueOf(method), ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, authorization(2L, "PUBLISHER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isMethodNotAllowed());
        verifyNoConfigurationAccess();
    }

    private void verifyNoConfigurationAccess() throws Exception {
        verifyNoInteractions(zoneMapper, sessionMapper, activityMapper, seatMapper);
        // Actuator may inspect the DataSource during startup; no request here should acquire a connection.
        verify(dataSource, never()).getConnection();
    }

    private ResultActions performCreate(Map<String, Object> request, int expectedStatus) throws Exception {
        var result = mvc.perform(post(ENDPOINT).header(HttpHeaders.AUTHORIZATION, authorization(2L, "PUBLISHER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is(expectedStatus));
        if (expectedStatus == 201) {
            result.andExpect(content().json(
                    "{\"createdCount\":2,\"seatIds\":[300,301]}", JsonCompareMode.STRICT));
        }
        return result;
    }

    private String authorization(long userId, String role) {
        return "Bearer " + JwtTestTokens.token(jwtEncoder, userId, role);
    }

    private Map<String, Object> validRequest() {
        return new HashMap<>(Map.of("seats", new ArrayList<>(List.of(seat(1, 1, 1), seat(1, 2, 2)))));
    }

    private Map<String, Object> seat(int rowNo, int seatNo, int priority) {
        return new HashMap<>(Map.of("rowNo", rowNo, "seatNo", seatNo, "allocationPriority", priority));
    }
}
