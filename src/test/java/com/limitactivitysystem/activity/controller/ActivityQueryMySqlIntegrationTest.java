package com.limitactivitysystem.activity.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.JwtTestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Runs against the configured MySQL schema; all fixtures roll back and Flyway is disabled.
@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class ActivityQueryMySqlIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private AppUserMapper userMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    private final Map<String, Activity> fixtures = new HashMap<>();
    private Activity closedPublishedActivity;

    @BeforeEach
    void insertFixtures() {
        AppUser publisher = new AppUser();
        publisher.setUsername("query_it_" + UUID.randomUUID().toString().replace("-", ""));
        publisher.setPasswordHash(passwordEncoder.encode("integration-test-password"));
        publisher.setRole("PUBLISHER");
        assertThat(userMapper.insert(publisher)).isEqualTo(1);
        for (String state : List.of("DRAFT", "PUBLISHED", "FINISHED", "CANCELLED")) {
            fixtures.put(state, insertActivity(publisher.getId(), state, 2099));
        }
        closedPublishedActivity = insertActivity(publisher.getId(), "PUBLISHED", 2000);
    }

    @Test
    void listFiltersPersistedStatesWithoutFilteringTheSaleWindow() throws Exception {
        String body = mvc.perform(get("/activities").queryParam("status", "DRAFT"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Long> ids = new ArrayList<>();
        for (JsonNode row : objectMapper.readTree(body)) {
            assertThat(row.path("status").asText()).isEqualTo("PUBLISHED");
            ids.add(row.path("activityId").asLong());
        }
        assertThat(ids).contains(fixtures.get("PUBLISHED").getActivityId(), closedPublishedActivity.getActivityId())
                .doesNotContain(fixtures.get("DRAFT").getActivityId(), fixtures.get("FINISHED").getActivityId(),
                        fixtures.get("CANCELLED").getActivityId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PUBLISHED", "FINISHED", "CANCELLED"})
    void detailsOnlyExposePersistedPublishedActivities(String state) throws Exception {
        Activity activity = fixtures.get(state);
        var response = mvc.perform(get("/activities/{id}", activity.getActivityId()));
        if ("PUBLISHED".equals(state)) {
            response.andExpect(status().isOk())
                    .andExpect(jsonPath("$.activityId").value(activity.getActivityId()))
                    .andExpect(jsonPath("$.status").value("PUBLISHED"));
        } else {
            response.andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"))
                    .andExpect(jsonPath("$.detail").value("Activity not found"));
        }
    }

    private Activity insertActivity(Long publisherId, String state, int year) {
        Activity activity = new Activity();
        activity.setPublisherId(publisherId);
        activity.setName("Query integration fixture");
        activity.setType("FREE");
        activity.setStatus(state);
        activity.setOpenTime(LocalDateTime.of(year, 11, 1, 9, 0));
        activity.setCloseTime(LocalDateTime.of(year, 11, 10, 18, 0));
        assertThat(activityMapper.insert(activity)).isEqualTo(1);
        return activity;
    }
}
