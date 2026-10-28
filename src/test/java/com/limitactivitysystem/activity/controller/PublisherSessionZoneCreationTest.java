package com.limitactivitysystem.activity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.mapper.ActivitySessionMapper;
import com.limitactivitysystem.activity.mapper.SessionZoneMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Real Security filters, application services and Spring JDBC transactions; persistence is mocked.
@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
class PublisherSessionZoneCreationTest {

    private static final String ENDPOINT = "/publisher/sessions/100/zones";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtEncoder jwtEncoder;
    @MockitoBean private AppUserMapper userMapper;
    @MockitoBean private ActivityMapper activityMapper;
    @MockitoBean private ActivitySessionMapper sessionMapper;
    @MockitoBean private SessionZoneMapper zoneMapper;
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
        given(userMapper.findById(2L)).willReturn(publisher);
        AppUser buyer = new AppUser();
        buyer.setId(1L);
        given(userMapper.findById(1L)).willReturn(buyer);

        ActivitySession session = new ActivitySession();
        session.setActivitySessionId(100L);
        session.setActivityId(10L);
        session.setCapacity(100);
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
        given(zoneMapper.insert(any(SessionZone.class))).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            SessionZone zone = invocation.getArgument(0);
            assertThat(zone.getZoneId()).isNull();
            zone.setZoneId(200L);
            return 1;
        });
    }

    @AfterEach
    void transactionIsClosed() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"PAID,128.50", "GIFT,0.00"})
    void publisherCreatesZoneThroughStoredOwnershipChain(String zoneType, String price) throws Exception {
        Map<String, Object> request = validRequest();
        request.put("zoneType", zoneType);
        request.put("price", new BigDecimal(price));
        performCreate(request, 201);

        ArgumentCaptor<SessionZone> saved = ArgumentCaptor.forClass(SessionZone.class);
        InOrder order = inOrder(connection, sessionMapper, activityMapper, zoneMapper);
        order.verify(connection).setAutoCommit(false);
        order.verify(sessionMapper).findById(100L);
        order.verify(activityMapper).findByIdForUpdate(10L);
        order.verify(zoneMapper).insert(saved.capture());
        order.verify(connection).commit();
        verify(connection, never()).rollback();
        SessionZone zone = saved.getValue();
        assertThat(zone.getSessionId()).isEqualTo(100L);
        assertThat(zone.getName()).isEqualTo("A 区");
        assertThat(zone.getZoneType()).isEqualTo(zoneType);
        assertThat(zone.getPrice()).isEqualByComparingTo(price);
        assertThat(zone.getTotalAmount()).isEqualTo(20);
        assertThat(zone.getRemainsAmount()).isEqualTo(20);
    }

    @Test
    void missingSessionReturns404() throws Exception {
        willReturn(null).given(sessionMapper).findById(100L);
        performCreate(validRequest(), 404).andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
        verifyNoInteractions(activityMapper, zoneMapper);
        verify(connection).rollback();
    }

    @Test
    void orphanSessionWithMissingActivityIsRejected() throws Exception {
        willReturn(null).given(activityMapper).findByIdForUpdate(10L);
        performCreate(validRequest(), 404).andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"));
        verifyNoInteractions(zoneMapper);
        verify(connection).rollback();
    }

    @Test
    void existingSessionIsInsufficientWhenItsActivityBelongsToAnotherPublisher() throws Exception {
        activity.setPublisherId(3L);
        performCreate(validRequest(), 403).andExpect(jsonPath("$.code").value("NOT_ACTIVITY_OWNER"));
        verifyNoInteractions(zoneMapper);
        verify(connection).rollback();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "GIFT"})
    void freeActivityRejectsBothZoneTypes(String zoneType) throws Exception {
        activity.setType("FREE");
        Map<String, Object> request = validRequest();
        request.put("zoneType", zoneType);
        request.put("price", "GIFT".equals(zoneType) ? BigDecimal.ZERO : BigDecimal.TEN);
        performCreate(request, 400);
        verifyNoInteractions(zoneMapper);
        verify(connection).rollback();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLISHED", "FINISHED", "CANCELLED"})
    void onlyDraftActivitiesAllowZoneConfiguration(String state) throws Exception {
        activity.setStatus(state);
        performCreate(validRequest(), 409).andExpect(jsonPath("$.code").value("ACTIVITY_NOT_DRAFT"));
        verifyNoInteractions(zoneMapper);
        verify(connection).rollback();
    }

    @ParameterizedTest
    @CsvSource({"PAID,0", "GIFT,1"})
    void priceMustMatchZoneType(String zoneType, String price) throws Exception {
        Map<String, Object> request = validRequest();
        request.put("zoneType", zoneType);
        request.put("price", new BigDecimal(price));
        performCreate(request, 400).andExpect(jsonPath("$.code").value("INVALID_ZONE_CONFIG"));
        verifyNoInteractions(zoneMapper);
        verify(connection).rollback();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1.001", "100000000.00"})
    void invalidDecimalPricesAreRejectedBeforeService(String price) throws Exception {
        Map<String, Object> request = validRequest();
        request.put("price", new BigDecimal(price));
        performCreate(request, 400).andExpect(jsonPath("$.code").value("INVALID_ZONE_CONFIG"));
        verifyNoInteractions(sessionMapper, activityMapper, zoneMapper);
        verify(dataSource, never()).getConnection();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void plannedAmountMustBePositive(int totalAmount) throws Exception {
        Map<String, Object> request = validRequest();
        request.put("totalAmount", totalAmount);
        performCreate(request, 400).andExpect(jsonPath("$.code").value("INVALID_CAPACITY"));
        verifyNoInteractions(sessionMapper, activityMapper, zoneMapper);
        verify(dataSource, never()).getConnection();
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "zoneType", "price", "totalAmount"})
    void requiredFieldsCannotBeOmitted(String field) throws Exception {
        Map<String, Object> request = validRequest();
        request.remove(field);
        performCreate(request, 400);
        verifyNoInteractions(sessionMapper, activityMapper, zoneMapper);
        verify(dataSource, never()).getConnection();
    }

    @Test
    void unsupportedZoneTypeIsRejected() throws Exception {
        Map<String, Object> request = validRequest();
        request.put("zoneType", "FREE");
        performCreate(request, 400);
        verifyNoInteractions(sessionMapper, activityMapper, zoneMapper);
        verify(dataSource, never()).getConnection();
    }

    @ParameterizedTest
    @CsvSource({"zoneId,false", "sessionId,false", "activityId,false", "publisherId,false", "remainsAmount,false",
            "zoneId,true", "sessionId,true", "activityId,true", "publisherId,true", "remainsAmount,true"})
    void clientCannotSupplyServerOwnedFields(String field, boolean nullValue) throws Exception {
        Map<String, Object> request = validRequest();
        request.put(field, nullValue ? null : 999);
        performCreate(request, 400);
        verifyNoInteractions(sessionMapper, activityMapper, zoneMapper);
        verify(dataSource, never()).getConnection();
    }

    @Test
    void duplicateNameReturns409AndRollsBack() throws Exception {
        willThrow(new DuplicateKeyException("duplicate zone name"))
                .given(zoneMapper).insert(any(SessionZone.class));
        performCreate(validRequest(), 409).andExpect(jsonPath("$.code").value("ZONE_ALREADY_EXISTS"));
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void userCannotCreateZones() throws Exception {
        mvc.perform(post(ENDPOINT).header(HttpHeaders.AUTHORIZATION, authorization(1L, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isForbidden())
                .andExpect(content().json("""
                        {"code":"FORBIDDEN","message":"Insufficient permission"}
                        """, JsonCompareMode.STRICT));
        verifyNoInteractions(sessionMapper, activityMapper, zoneMapper);
        verify(dataSource, never()).getConnection();
    }

    @Test
    void anonymousCannotCreateZones() throws Exception {
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(sessionMapper, activityMapper, zoneMapper);
        verify(dataSource, never()).getConnection();
    }

    @Test
    void disabledPublisherCannotUseOldToken() throws Exception {
        publisher.setStatus("DISABLED");
        performCreate(validRequest(), 401);
        verifyNoInteractions(sessionMapper, activityMapper, zoneMapper);
        verify(dataSource, never()).getConnection();
    }

    @Test
    void serviceRechecksActiveStatusAfterAuthentication() throws Exception {
        AppUser disabled = new AppUser();
        disabled.setId(2L);
        disabled.setRole("PUBLISHER");
        disabled.setStatus("DISABLED");
        given(userMapper.findById(2L)).willReturn(publisher, disabled);
        performCreate(validRequest(), 401);
        verifyNoInteractions(sessionMapper, activityMapper, zoneMapper);
        verify(connection).rollback();
    }

    @Test
    void serviceRejectsRoleRevokedAfterAuthentication() throws Exception {
        AppUser revoked = new AppUser();
        revoked.setId(2L);
        given(userMapper.findById(2L)).willReturn(publisher, revoked);
        performCreate(validRequest(), 403);
        verifyNoInteractions(sessionMapper, activityMapper, zoneMapper);
        verify(connection).rollback();
    }

    private ResultActions performCreate(Map<String, Object> request, int expectedStatus) throws Exception {
        var result = mvc.perform(post(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, authorization(2L, "PUBLISHER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is(expectedStatus));
        if (expectedStatus == 201) {
            result.andExpect(content().json(
                    "{\"zoneId\":200,\"totalAmount\":20,\"remainsAmount\":20}", JsonCompareMode.STRICT));
        }
        return result;
    }

    private String authorization(long userId, String role) {
        return "Bearer " + JwtTestTokens.token(jwtEncoder, userId, role);
    }

    private Map<String, Object> validRequest() {
        Map<String, Object> request = new HashMap<>();
        request.put("name", "A 区");
        request.put("zoneType", "PAID");
        request.put("price", new BigDecimal("128.50"));
        request.put("totalAmount", 20);
        return request;
    }
}
