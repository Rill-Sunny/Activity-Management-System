package com.limitactivitysystem.activity.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.limitactivitysystem.activity.exception.ActivityErrorCode;
import com.limitactivitysystem.activity.exception.ActivityException;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.activity.model.SessionSeat;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.activity.service.ActivityOwnershipService;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.whitelist.service.QualificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Real HTTP filters, services, MyBatis and MySQL. All injected IDs refer to existing resources.
@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import(ActivityResourceReferenceMySqlIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class ActivityResourceReferenceMySqlIntegrationTest extends ActivityMySqlTestSupport {

    @Autowired private ActivityOwnershipService ownership;
    @Autowired private QualificationService qualification;

    @AfterEach
    void removeWhitelistFixturesFromTheIsolatedSchema() {
        jdbc.update("DELETE FROM whitelist_member");
        jdbc.update("DELETE FROM whitelist_config");
    }

    @ParameterizedTest(name = "same owner={0}, injected reference={1}")
    @MethodSource("configurationReferences")
    void rejectsExistingCrossChainIdsInConfigurationRequests(boolean sameOwner, Injection injection) throws Exception {
        Chains chains = insertChains(sameOwner);
        Request request = configurationRequest(chains.first(), injection.operation);
        request.body().put(injection.field, reference(chains.second(), injection.field));
        Map<String, List<Map<String, Object>>> before = snapshot();

        assertProblem(postJson(request.path(), publisher, request.body()), 400, "INVALID_ACTIVITY_REQUEST");

        assertThat(snapshot()).isEqualTo(before);
    }

    static Stream<Arguments> configurationReferences() {
        return Stream.of(true, false).flatMap(sameOwner -> Arrays.stream(Injection.values())
                .map(injection -> Arguments.of(sameOwner, injection)));
    }

    @ParameterizedTest(name = "same owner={0}, later seat injects {1}")
    @CsvSource({"true,activityId", "true,sessionId", "true,zoneId", "true,seatId",
            "false,activityId", "false,sessionId", "false,zoneId", "false,seatId"})
    void rejectsTheWholeSeatBatchWhenALaterSeatReferencesTheOtherChain(boolean sameOwner, String field)
            throws Exception {
        Chains chains = insertChains(sameOwner);
        Map<String, Object> laterSeat = new HashMap<>(Map.of("rowNo", 1, "seatNo", 3, "allocationPriority", 3));
        laterSeat.put(field, reference(chains.second(), field));
        Map<String, Object> body = Map.of("seats", List.of(
                Map.of("rowNo", 1, "seatNo", 2, "allocationPriority", 2), laterSeat));
        Map<String, List<Map<String, Object>>> before = snapshot();

        assertProblem(postJson(seatPath(chains.first()), publisher, body), 400, "INVALID_ACTIVITY_REQUEST");

        // Includes every row and relationship in both chains, so an inserted first seat also fails this assertion.
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ownershipAlwaysFollowsTheActualStoredParentsOfTheSelectedZoneAndSeat(boolean sameOwner) throws Exception {
        Chains chains = insertChains(sameOwner);
        Chain second = chains.second();
        Map<String, List<Map<String, Object>>> before = snapshot();
        if (sameOwner) {
            var zoneOwner = asPublisher(() -> ownership.assertZoneOwner(second.zone().getZoneId()));
            var seatOwner = asPublisher(() -> ownership.assertSeatOwner(second.seat().getSeatId()));
            assertThat(zoneOwner.session().getActivitySessionId()).isEqualTo(second.session().getActivitySessionId())
                    .isNotEqualTo(chains.first().session().getActivitySessionId());
            assertThat(zoneOwner.activity().getActivityId()).isEqualTo(second.activity().getActivityId());
            assertThat(seatOwner.zone().getZoneId()).isEqualTo(second.zone().getZoneId());
            assertThat(seatOwner.session().getActivitySessionId()).isEqualTo(second.session().getActivitySessionId());
            assertThat(seatOwner.activity().getActivityId()).isEqualTo(second.activity().getActivityId());
        } else {
            assertThatThrownBy(() -> asPublisher(() -> ownership.assertZoneOwner(second.zone().getZoneId())))
                    .isInstanceOfSatisfying(ActivityException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ActivityErrorCode.NOT_ACTIVITY_OWNER));
            assertThatThrownBy(() -> asPublisher(() -> ownership.assertSeatOwner(second.seat().getSeatId())))
                    .isInstanceOfSatisfying(ActivityException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ActivityErrorCode.NOT_ACTIVITY_OWNER));
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest(name = "same owner={0}, operation={1}")
    @CsvSource({"true,SESSION", "true,ZONE", "true,SEAT", "false,SESSION", "false,ZONE", "false,SEAT"})
    void publishedSecondChainCannotBorrowTheFirstChainsDraftStatus(boolean sameOwner, Operation operation)
            throws Exception {
        Chains chains = insertChains(sameOwner);
        var secondOwner = sameOwner ? publisher : otherPublisher;
        postJson(seatPath(chains.second()), secondOwner, seatsBody()).andExpect(status().isCreated());
        publish(chains.second().activity(), secondOwner).andExpect(status().isNoContent());
        assertThat(activityMapper.findById(chains.first().activity().getActivityId()).getStatus()).isEqualTo("DRAFT");
        Map<String, List<Map<String, Object>>> before = snapshot();
        Request request = configurationRequest(chains.second(), operation);
        if (operation == Operation.SEAT) {
            // Use unused positions, so a missing DRAFT check would cause a successful insert.
            request.body().put("seats", List.of(Map.of("rowNo", 1, "seatNo", 4, "allocationPriority", 4)));
        }

        assertProblem(postJson(request.path(), publisher, request.body()), sameOwner ? 409 : 403,
                sameOwner ? "ACTIVITY_NOT_DRAFT" : "NOT_ACTIVITY_OWNER");

        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void validConfigurationWritesAttachOnlyToThePathSelectedParents(boolean sameOwner) throws Exception {
        Chains chains = insertChains(sameOwner);
        Chain first = chains.first();
        JsonNode secondBefore = snapshotChain(chains.second());

        long sessionId = create("/publisher/activities/" + first.activity().getActivityId() + "/sessions", sessionBody())
                .path("activitySessionId").asLong();
        assertThat(sessionMapper.findById(sessionId).getActivityId()).isEqualTo(first.activity().getActivityId());

        long zoneId = create("/publisher/sessions/" + first.session().getActivitySessionId() + "/zones", zoneBody())
                .path("zoneId").asLong();
        assertThat(zoneMapper.findById(zoneId).getSessionId()).isEqualTo(first.session().getActivitySessionId());

        JsonNode createdSeats = create(seatPath(first), seatsBody()).path("seatIds");
        assertThat(createdSeats.size()).isEqualTo(2);
        for (JsonNode id : createdSeats) {
            assertThat(seatMapper.findById(id.asLong()).getZoneId()).isEqualTo(first.zone().getZoneId());
        }
        assertThat(snapshotChain(chains.second())).isEqualTo(secondBefore);
    }

    @ParameterizedTest(name = "same owner={0}, session whitelist configured={1}")
    @CsvSource({"true,false", "true,true", "false,false", "false,true"})
    void qualificationRejectsMixedActivityAndSessionIdsEvenWhenBothMatchingPairsAreEligible(
            boolean sameOwner, boolean configured) throws Exception {
        Chains chains = insertChains(sameOwner);
        Chain first = chains.first();
        Chain second = chains.second();
        if (configured) {
            enableWhitelist("sessions", second.session().getActivitySessionId(), sameOwner);
            postJson(whitelistPath("sessions", second.session().getActivitySessionId()) + "/members",
                    sameOwner ? publisher : otherPublisher, Map.of("userId", buyer.getId()))
                    .andExpect(status().isCreated());
        }
        Map<String, List<Map<String, Object>>> before = snapshot();

        assertThat(eligible(first.activity(), first.session())).isTrue();
        assertThat(eligible(second.activity(), second.session())).isTrue();
        assertThat(eligible(first.activity(), second.session())).isFalse();
        assertThat(eligible(second.activity(), first.session())).isFalse();

        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest(name = "same owner={0}, whitelist scope={1}")
    @CsvSource({"true,activities", "true,sessions", "false,activities", "false,sessions"})
    void cannotRedirectMembershipToTheOtherChainsExistingConfig(boolean sameOwner, String scope) throws Exception {
        Chains chains = insertChains(sameOwner);
        long firstId = scopeId(scope, chains.first());
        long secondId = scopeId(scope, chains.second());
        long firstConfig = enableWhitelist(scope, firstId, true);
        long secondConfig = enableWhitelist(scope, secondId, sameOwner);
        assertThat(secondConfig).isNotEqualTo(firstConfig);
        Map<String, List<Map<String, Object>>> before = snapshot();

        assertProblem(postJson(whitelistPath(scope, firstId) + "/members", publisher,
                        Map.of("userId", buyer.getId(), "configId", secondConfig)),
                400, "INVALID_WHITELIST_REQUEST");

        assertThat(snapshot()).isEqualTo(before);
    }

    private Chains insertChains(boolean sameOwner) {
        Chain first = insertChain(insertDraft(publisher, "PAID"));
        Chain second = insertChain(insertDraft(sameOwner ? publisher : otherPublisher, "PAID"));
        assertThat(first.activity().getActivityId()).isNotEqualTo(second.activity().getActivityId());
        assertThat(first.session().getActivitySessionId()).isNotEqualTo(second.session().getActivitySessionId());
        assertThat(first.zone().getZoneId()).isNotEqualTo(second.zone().getZoneId());
        assertThat(first.seat().getSeatId()).isNotEqualTo(second.seat().getSeatId());
        return new Chains(first, second);
    }

    private Chain insertChain(Activity activity) {
        ActivitySession session = insertSession(activity);
        SessionZone zone = insertZone(session, "PAID", 3, 1);
        return new Chain(activity, session, zone, seatMapper.findByZoneId(zone.getZoneId()).get(0));
    }

    private Request configurationRequest(Chain chain, Operation operation) {
        return switch (operation) {
            case SESSION -> new Request("/publisher/activities/" + chain.activity().getActivityId() + "/sessions",
                    new HashMap<>(sessionBody()));
            case ZONE -> new Request("/publisher/sessions/" + chain.session().getActivitySessionId() + "/zones", zoneBody());
            case SEAT -> new Request(seatPath(chain), new HashMap<>(seatsBody()));
        };
    }

    private Map<String, Object> zoneBody() {
        return new HashMap<>(Map.of("name", "new-zone", "zoneType", "PAID", "price", 10, "totalAmount", 2));
    }

    private Map<String, Object> seatsBody() {
        return Map.of("seats", List.of(Map.of("rowNo", 1, "seatNo", 2, "allocationPriority", 2),
                Map.of("rowNo", 1, "seatNo", 3, "allocationPriority", 3)));
    }

    private String seatPath(Chain chain) { return "/publisher/zones/" + chain.zone().getZoneId() + "/seats"; }

    private Long reference(Chain chain, String field) {
        return switch (field) {
            case "activityId" -> chain.activity().getActivityId();
            case "sessionId" -> chain.session().getActivitySessionId();
            case "zoneId" -> chain.zone().getZoneId();
            case "seatId" -> chain.seat().getSeatId();
            default -> throw new IllegalArgumentException("Unknown test reference: " + field);
        };
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : List.of("activity", "activity_session", "session_zone", "session_seat",
                "whitelist_config", "whitelist_member")) {
            rows.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1"));
        }
        return rows;
    }

    private JsonNode snapshotChain(Chain chain) {
        return objectMapper.valueToTree(List.of(activityMapper.findById(chain.activity().getActivityId()),
                sessionMapper.findByActivityId(chain.activity().getActivityId()),
                zoneMapper.findBySessionId(chain.session().getActivitySessionId()),
                seatMapper.findByZoneId(chain.zone().getZoneId())));
    }

    private long enableWhitelist(String scope, Long scopeId, boolean ownedByPublisher) throws Exception {
        String response = mvc.perform(put(whitelistPath(scope, scopeId)).header(HttpHeaders.AUTHORIZATION,
                        bearer(ownedByPublisher ? publisher : otherPublisher)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("configId").asLong();
    }

    private String whitelistPath(String scope, Long scopeId) { return "/publisher/" + scope + "/" + scopeId + "/whitelist"; }

    private long scopeId(String scope, Chain chain) {
        return "activities".equals(scope) ? chain.activity().getActivityId() : chain.session().getActivitySessionId();
    }

    private boolean eligible(Activity activity, ActivitySession session) {
        return qualification.canParticipate(buyer.getId(), activity.getActivityId(), session.getActivitySessionId());
    }

    private void assertProblem(ResultActions result, int httpStatus, String code) throws Exception {
        result.andExpect(status().is(httpStatus)).andExpect(jsonPath("$.status").value(httpStatus))
                .andExpect(jsonPath("$.code").value(code));
    }

    enum Operation { SESSION, ZONE, SEAT }

    enum Injection {
        SESSION_ACTIVITY(Operation.SESSION, "activityId"),
        ZONE_ACTIVITY(Operation.ZONE, "activityId"),
        ZONE_SESSION(Operation.ZONE, "sessionId"),
        ZONE_EXISTING_ZONE(Operation.ZONE, "zoneId"),
        SEAT_ACTIVITY(Operation.SEAT, "activityId"),
        SEAT_SESSION(Operation.SEAT, "sessionId"),
        SEAT_ZONE(Operation.SEAT, "zoneId");

        private final Operation operation;
        private final String field;

        Injection(Operation operation, String field) { this.operation = operation; this.field = field; }
    }

    private record Request(String path, Map<String, Object> body) { }
    private record Chain(Activity activity, ActivitySession session, SessionZone zone, SessionSeat seat) { }
    private record Chains(Chain first, Chain second) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        IsolatedQualificationMySqlDataSource dataSource(DataSourceProperties properties) throws Exception {
            return new IsolatedQualificationMySqlDataSource(properties);
        }
    }
}
