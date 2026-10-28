package com.limitactivitysystem.activity.integration;

import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.security.JwtTestTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class ActivityPublishingMySqlIntegrationTest extends ActivityMySqlTestSupport {

    @Test
    void ordinaryUserCannotCreateAnActivity() throws Exception {
        postJson("/publisher/activities", buyer, activityBody("PAID"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity WHERE publisher_id = ?",
                Long.class, buyer.getId())).isZero();
    }

    @Test
    void completePaidActivityIsCreatedPublishedAndReadThroughRealSecurityAndMyBatis() throws Exception {
        long activityId = create("/publisher/activities", activityBody("PAID")).path("activityId").asLong();
        Activity activity = activityMapper.findById(activityId);
        assertThat(activity.getPublisherId()).isEqualTo(publisher.getId());
        assertThat(activity.getStatus()).isEqualTo("DRAFT");
        long sessionId = create("/publisher/activities/" + activityId + "/sessions", sessionBody())
                .path("activitySessionId").asLong();
        for (String type : List.of("PAID", "GIFT")) {
            int amount = "PAID".equals(type) ? 8 : 2;
            long zoneId = create("/publisher/sessions/" + sessionId + "/zones", Map.of(
                    "name", type, "zoneType", type, "price", "PAID".equals(type) ? 10 : 0, "totalAmount", amount))
                    .path("zoneId").asLong();
            List<Map<String, Integer>> seats = java.util.stream.IntStream.rangeClosed(1, amount)
                    .mapToObj(position -> Map.of("rowNo", 1, "seatNo", position, "allocationPriority", position)).toList();
            var response = create("/publisher/zones/" + zoneId + "/seats", Map.of("seats", seats));
            assertThat(response.path("createdCount").asInt()).isEqualTo(amount);
            assertThat(response.path("seatIds").size()).isEqualTo(amount);
            List<Long> responseIds = new ArrayList<>();
            for (var id : response.path("seatIds")) {
                assertThat(id.isIntegralNumber()).isTrue();
                assertThat(id.asLong()).isPositive();
                responseIds.add(id.asLong());
            }
            assertThat(responseIds).containsExactlyElementsOf(
                    seatMapper.findByZoneId(zoneId).stream().map(seat -> seat.getSeatId()).toList());
            assertThat(seatMapper.countByZoneId(zoneId)).isEqualTo(amount);
            assertThat(zoneMapper.findById(zoneId).getTotalAmount()).isEqualTo(amount);
        }
        publish(activity, publisher).andExpect(status().isNoContent());
        assertStatus(activity, "PUBLISHED");
        mvc.perform(get("/activities/{id}", activityId).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void publisherCannotConfigureOrPublishAnotherPublishersActivity() throws Exception {
        Activity activity = insertDraft(otherPublisher, "PAID");
        assertProblem(postJson("/publisher/activities/" + activity.getActivityId() + "/sessions", publisher, sessionBody()),
                403, "NOT_ACTIVITY_OWNER");
        assertProblem(publish(activity, publisher), 403, "NOT_ACTIVITY_OWNER");
        assertThat(sessionMapper.countByActivityId(activity.getActivityId())).isZero();
        assertStatus(activity, "DRAFT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"FREE", "PAID"})
    void persistedCrossTableSessionTypeViolationsPreventPublication(String type) throws Exception {
        Activity activity = insertDraft(publisher, type);
        ActivitySession session = new ActivitySession();
        session.setActivityId(activity.getActivityId());
        session.setStartTime(activity.getCloseTime().plusDays(1));
        session.setEndTime(session.getStartTime().plusHours(2));
        session.setCapacity(10);
        session.setMaxTicketsPerUser("PAID".equals(type) ? 2 : null);
        session.setSeatMode("FREE".equals(type) ? "SELECT_SEAT" : null);
        persistSession(session); // SQL CHECKs allow these rows; the parent-type rule belongs to the Service.
        assertProblem(publish(activity, publisher), 400, "INVALID_SESSION_CONFIG");
        assertStatus(activity, "DRAFT");
        assertThat(sessionMapper.findById(session.getActivitySessionId()).getSeatMode()).isEqualTo(session.getSeatMode());
    }

    @Test
    void activityWithoutAnySessionsCannotBePublished() throws Exception {
        Activity activity = insertDraft(publisher, "FREE");
        assertProblem(publish(activity, publisher), 400, "INVALID_SESSION_CONFIG");
        assertStatus(activity, "DRAFT");
    }

    @Test
    void earliestSessionRatherThanInsertionOrderConstrainsTheActivityWindow() throws Exception {
        Activity activity = insertDraft(publisher, "FREE");
        insertSession(activity);
        ActivitySession earlier = new ActivitySession();
        earlier.setActivityId(activity.getActivityId());
        earlier.setCapacity(10);
        earlier.setStartTime(activity.getCloseTime().minusMinutes(1));
        earlier.setEndTime(activity.getCloseTime().plusHours(1));
        persistSession(earlier);
        assertProblem(publish(activity, publisher), 400, "INVALID_ACTIVITY_WINDOW");
        assertStatus(activity, "DRAFT");
    }

    @Test
    void paidAndGiftCapacityTogetherCannotExceedTheRealSessionCapacity() throws Exception {
        Activity activity = insertDraft(publisher, "PAID");
        ActivitySession session = insertSession(activity);
        SessionZone paid = insertZone(session, "PAID", 8, 8);
        SessionZone gift = insertZone(session, "GIFT", 3, 3);
        assertProblem(publish(activity, publisher), 400, "ZONE_CAPACITY_EXCEEDED");
        assertStatus(activity, "DRAFT");
        assertThat(zoneMapper.sumTotalAmountBySessionId(session.getActivitySessionId())).isEqualTo(11);
        assertThat(seatMapper.countByZoneId(paid.getZoneId())).isEqualTo(8);
        assertThat(seatMapper.countByZoneId(gift.getZoneId())).isEqualTo(3);
    }

    @Test
    void nineActualSeatsDoNotSilentlyChangeAPlanOfTen() throws Exception {
        Activity activity = insertDraft(publisher, "PAID");
        SessionZone zone = insertZone(insertSession(activity), "PAID", 10, 9);
        assertProblem(publish(activity, publisher), 400, "SEAT_COUNT_MISMATCH");
        assertStatus(activity, "DRAFT");
        assertThat(zoneMapper.findById(zone.getZoneId()).getTotalAmount()).isEqualTo(10);
        assertThat(zoneMapper.findById(zone.getZoneId()).getRemainsAmount()).isEqualTo(10);
        assertThat(seatMapper.countByZoneId(zone.getZoneId())).isEqualTo(9);
    }

    @Test
    void publishedActivityRejectsAdditionalSeatsInTheDatabase() throws Exception {
        Activity activity = insertDraft(publisher, "PAID");
        SessionZone zone = insertZone(insertSession(activity), "PAID", 10, 10);
        publish(activity, publisher).andExpect(status().isNoContent());
        assertProblem(postJson("/publisher/zones/" + zone.getZoneId() + "/seats", publisher, oneSeatRequest(11)),
                409, "ACTIVITY_NOT_DRAFT");
        assertStatus(activity, "PUBLISHED");
        assertThat(seatMapper.countByZoneId(zone.getZoneId())).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH"})
    void publishedZonePriceCannotBeChangedThroughOrdinaryEditing(String method) throws Exception {
        Activity activity = insertDraft(publisher, "PAID");
        SessionZone zone = insertZone(insertSession(activity), "PAID", 10, 10);
        publish(activity, publisher).andExpect(status().isNoContent());
        mvc.perform(request(HttpMethod.valueOf(method), "/publisher/zones/" + zone.getZoneId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(publisher))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\":1.00}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(404, 405));
        assertThat(zoneMapper.findById(zone.getZoneId()).getPrice()).isEqualByComparingTo("10.00");
        assertStatus(activity, "PUBLISHED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH"})
    void ordinaryEditingCannotChangeAnotherPublishersActivity(String method) throws Exception {
        Activity activity = insertDraft(otherPublisher, "PAID");
        mvc.perform(request(HttpMethod.valueOf(method), "/publisher/activities/" + activity.getActivityId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(publisher))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"FREE\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(404, 405));
        assertThat(activityMapper.findById(activity.getActivityId()).getType()).isEqualTo("PAID");
        assertStatus(activity, "DRAFT");
    }

    @Test
    void duplicateSeatInABatchRollsBackEarlierRowsFromTheSameInsert() throws Exception {
        Activity activity = insertDraft(publisher, "PAID");
        SessionZone zone = insertZone(insertSession(activity), "PAID", 10, 1);
        Map<String, Object> body = Map.of("seats", List.of(
                Map.of("rowNo", 1, "seatNo", 2, "allocationPriority", 2),
                Map.of("rowNo", 1, "seatNo", 1, "allocationPriority", 1)));
        assertProblem(postJson("/publisher/zones/" + zone.getZoneId() + "/seats", publisher, body),
                409, "SEAT_ALREADY_EXISTS");
        assertThat(seatMapper.countByZoneId(zone.getZoneId())).isEqualTo(1);
        assertThat(seatMapper.findByZoneId(zone.getZoneId())).allSatisfy(seat -> assertThat(seat.getSeatNo()).isEqualTo(1));
        assertStatus(activity, "DRAFT");
    }

    @Test
    void conditionalPublicationUpdateCannotRepublishANonDraftRow() {
        Activity activity = insertDraft(publisher, "FREE");
        assertThat(activityMapper.updateStatus(activity.getActivityId(), "PUBLISHED")).isEqualTo(1);
        assertThat(activityMapper.updateStatus(activity.getActivityId(), "PUBLISHED")).isZero();
        assertStatus(activity, "PUBLISHED");
    }

    @ParameterizedTest(name = "{0} {1}.{2}")
    @MethodSource("frozenOrdinaryEdits")
    void ordinaryEditsLeaveEveryPublishedCoreFieldUnchanged(String method, String resource,
                                                           String field, Object value) throws Exception {
        Activity activity = insertDraft(publisher, "PAID");
        var session = insertSession(activity);
        SessionZone zone = insertZone(session, "PAID", 10, 10);
        publish(activity, publisher).andExpect(status().isNoContent());
        com.fasterxml.jackson.databind.JsonNode before = objectMapper.valueToTree(List.of(
                activityMapper.findById(activity.getActivityId()), sessionMapper.findById(session.getActivitySessionId()),
                zoneMapper.findById(zone.getZoneId()), seatMapper.findByZoneId(zone.getZoneId())));
        String path = switch (resource) {
            case "activity" -> "/publisher/activities/" + activity.getActivityId();
            case "session" -> "/publisher/sessions/" + session.getActivitySessionId();
            case "zone" -> "/publisher/zones/" + zone.getZoneId();
            case "seat" -> "/publisher/seats/" + seatMapper.findByZoneId(zone.getZoneId()).get(0).getSeatId();
            default -> throw new AssertionError("Unknown fixture resource");
        };
        // Generic editing is not exposed today. An implemented editor must return the DRAFT conflict.
        var response = mvc.perform(request(HttpMethod.valueOf(method), path)
                        .header(HttpHeaders.AUTHORIZATION, bearer(publisher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(field, value))))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(404, 405, 409));
        if (response.andReturn().getResponse().getStatus() == 409) {
            response.andExpect(jsonPath("$.code").value("ACTIVITY_NOT_DRAFT"));
        }
        com.fasterxml.jackson.databind.JsonNode after = objectMapper.valueToTree(List.of(
                activityMapper.findById(activity.getActivityId()), sessionMapper.findById(session.getActivitySessionId()),
                zoneMapper.findById(zone.getZoneId()), seatMapper.findByZoneId(zone.getZoneId())));
        assertThat(after).isEqualTo(before);
    }

    static Stream<Arguments> frozenOrdinaryEdits() {
        return Stream.<Object[]>of(
                new Object[]{"activity", "type", "FREE"},
                new Object[]{"activity", "openTime", "2099-11-02T09:00:00"},
                new Object[]{"activity", "closeTime", "2099-11-09T18:00:00"},
                new Object[]{"session", "capacity", 20},
                new Object[]{"session", "maxTicketsPerUser", 3},
                new Object[]{"session", "seatMode", "AUTO_ASSIGN"},
                new Object[]{"session", "startTime", "2099-11-11T19:00:00"},
                new Object[]{"session", "endTime", "2099-11-11T22:00:00"},
                new Object[]{"zone", "totalAmount", 20},
                new Object[]{"zone", "price", 1},
                new Object[]{"seat", "rowNo", 2},
                new Object[]{"seat", "seatNo", 2},
                new Object[]{"seat", "allocationPriority", 99})
                .flatMap(change -> Stream.of("PUT", "PATCH")
                        .map(method -> Arguments.of(method, change[0], change[1], change[2])));
    }

    @ParameterizedTest
    @ValueSource(strings = {"session", "zone"})
    void publishedActivityRejectsAdditionalSessionsAndZones(String resource) throws Exception {
        Activity activity = insertDraft(publisher, "PAID");
        var session = insertSession(activity);
        SessionZone zone = insertZone(session, "PAID", 10, 10);
        publish(activity, publisher).andExpect(status().isNoContent());
        if (resource.equals("session")) {
            assertProblem(postJson("/publisher/activities/" + activity.getActivityId() + "/sessions", publisher,
                    sessionBody()), 409, "ACTIVITY_NOT_DRAFT");
        } else {
            assertProblem(postJson("/publisher/sessions/" + session.getActivitySessionId() + "/zones", publisher,
                    Map.of("name", "new zone", "zoneType", "PAID", "price", 10, "totalAmount", 1)),
                    409, "ACTIVITY_NOT_DRAFT");
        }
        assertThat(sessionMapper.countByActivityId(activity.getActivityId())).isEqualTo(1);
        assertThat(zoneMapper.findBySessionId(session.getActivitySessionId())).hasSize(1);
        assertThat(seatMapper.countByZoneId(zone.getZoneId())).isEqualTo(10);
        assertStatus(activity, "PUBLISHED");
    }

    private void assertStatus(Activity activity, String expected) {
        // Outside a test transaction, each read observes the actual committed outcome.
        assertThat(activityMapper.findById(activity.getActivityId()).getStatus()).isEqualTo(expected);
    }

    private void assertProblem(ResultActions result, int httpStatus, String code) throws Exception {
        result.andExpect(status().is(httpStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(httpStatus)).andExpect(jsonPath("$.code").value(code));
    }
}
