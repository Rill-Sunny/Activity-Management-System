package com.limitactivitysystem.activity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.service.PublisherActivityService;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.CurrentUserProvider;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Real JWT decoding, CurrentUserProvider, service and Security filters; only persistence is mocked.
@WebMvcTest(controllers = PublisherActivityController.class, properties = JwtTestTokens.SECRET_PROPERTY)
@Import({SecurityConfig.class, CurrentUserProvider.class, PublisherActivityService.class})
class PublisherActivityCreationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtEncoder jwtEncoder;
    @MockitoBean private AppUserMapper appUserMapper;
    @MockitoBean private ActivityMapper activityMapper;

    private AppUser publisher;

    @BeforeEach
    void setUp() {
        AppUser buyer = new AppUser();
        buyer.setId(1L);
        buyer.setUsername("buyer");
        given(appUserMapper.findById(1L)).willReturn(buyer);

        publisher = new AppUser();
        publisher.setId(2L);
        publisher.setUsername("publisher");
        publisher.setRole("PUBLISHER");
        given(appUserMapper.findById(2L)).willReturn(publisher);
        given(activityMapper.insert(any(Activity.class))).willAnswer(invocation -> {
            Activity activity = invocation.getArgument(0);
            assertThat(activity.getActivityId()).isNull();
            activity.setActivityId(42L);
            return 1;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"FREE", "PAID"})
    void publisherCreatesDraftOwnedByAuthenticatedUser(String type) throws Exception {
        Map<String, Object> request = validRequest();
        request.put("type", type);
        mvc.perform(post("/publisher/activities")
                        .header(HttpHeaders.AUTHORIZATION, authorization(2L, "PUBLISHER"))
                        .queryParam("publisherId", "999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(content().json("{\"activityId\":42,\"status\":\"DRAFT\"}", JsonCompareMode.STRICT));

        ArgumentCaptor<Activity> saved = ArgumentCaptor.forClass(Activity.class);
        verify(activityMapper).insert(saved.capture());
        Activity activity = saved.getValue();
        assertThat(activity.getPublisherId()).isEqualTo(2L);
        assertThat(activity.getStatus()).isEqualTo("DRAFT");
        assertThat(activity.getType()).isEqualTo(type);
        assertThat(activity.getName()).isEqualTo("2026 校园音乐节");
        assertThat(activity.getDescription()).isEqualTo("demo");
        assertThat(activity.getOpenTime()).isEqualTo(LocalDateTime.of(2026, 11, 1, 9, 0));
        assertThat(activity.getCloseTime()).isEqualTo(LocalDateTime.of(2026, 11, 10, 18, 0));
    }

    @Test
    void userCannotCreateActivity() throws Exception {
        mvc.perform(post("/publisher/activities")
                        .header(HttpHeaders.AUTHORIZATION, authorization(1L, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isForbidden())
                .andExpect(content().json("""
                        {"code":"FORBIDDEN","message":"Insufficient permission"}
                        """, JsonCompareMode.STRICT));
        verifyNoInteractions(activityMapper);
    }

    @Test
    void anonymousCannotCreateActivity() throws Exception {
        mvc.perform(post("/publisher/activities").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("""
                        {"code":"UNAUTHORIZED","message":"Authentication is required"}
                        """, JsonCompareMode.STRICT));
        verifyNoInteractions(activityMapper);
    }

    @ParameterizedTest
    @CsvSource({"publisherId,false", "status,false", "activityId,false",
            "publisherId,true", "status,true", "activityId,true"})
    void clientCannotProvideServerOwnedFieldsEvenWhenNull(String field, boolean nullValue) throws Exception {
        Map<String, Object> request = validRequest();
        request.put(field, nullValue ? null : "status".equals(field) ? "PUBLISHED" : 999);
        expectInvalidRequest(request);
    }

    @Test
    void descriptionIsOptional() throws Exception {
        Map<String, Object> request = validRequest();
        request.remove("description");
        mvc.perform(post("/publisher/activities")
                        .header(HttpHeaders.AUTHORIZATION, authorization(2L, "PUBLISHER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
        ArgumentCaptor<Activity> saved = ArgumentCaptor.forClass(Activity.class);
        verify(activityMapper).insert(saved.capture());
        assertThat(saved.getValue().getDescription()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"name,'   '", "type,OTHER", "openTime,not-a-date"})
    void invalidBusinessFieldsAreRejectedBeforeInsert(String field, String value) throws Exception {
        Map<String, Object> request = validRequest();
        request.put(field, value);
        expectInvalidRequest(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "type", "openTime", "closeTime"})
    void requiredFieldsCannotBeOmitted(String field) throws Exception {
        Map<String, Object> request = validRequest();
        request.remove(field);
        expectInvalidRequest(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-01T09:00:00", "2026-11-01T08:59:59"})
    void closeTimeMustBeAfterOpenTime(String closeTime) throws Exception {
        Map<String, Object> request = validRequest();
        request.put("closeTime", closeTime);
        expectInvalidRequest(request).andExpect(jsonPath("$.code").value("INVALID_ACTIVITY_WINDOW"));
    }

    @Test
    void disabledPublisherCannotCreateWithOldToken() throws Exception {
        publisher.setStatus("DISABLED");
        mvc.perform(post("/publisher/activities")
                        .header(HttpHeaders.AUTHORIZATION, authorization(2L, "PUBLISHER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(activityMapper);
    }

    @Test
    void serviceRechecksActiveStatusAfterFilterAuthentication() throws Exception {
        AppUser disabled = new AppUser();
        disabled.setId(2L);
        disabled.setRole("PUBLISHER");
        disabled.setStatus("DISABLED");
        given(appUserMapper.findById(2L)).willReturn(publisher, disabled);
        mvc.perform(post("/publisher/activities")
                        .header(HttpHeaders.AUTHORIZATION, authorization(2L, "PUBLISHER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(activityMapper);
    }

    @Test
    void serviceRejectsRoleRevokedAfterFilterAuthentication() throws Exception {
        AppUser formerPublisher = new AppUser();
        formerPublisher.setId(2L);
        given(appUserMapper.findById(2L)).willReturn(publisher, formerPublisher);
        mvc.perform(post("/publisher/activities")
                        .header(HttpHeaders.AUTHORIZATION, authorization(2L, "PUBLISHER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(activityMapper);
    }

    private ResultActions expectInvalidRequest(Map<String, Object> request) throws Exception {
        var result = mvc.perform(post("/publisher/activities")
                        .header(HttpHeaders.AUTHORIZATION, authorization(2L, "PUBLISHER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(activityMapper);
        return result;
    }

    private String authorization(long userId, String role) {
        return "Bearer " + JwtTestTokens.token(jwtEncoder, userId, role);
    }

    private Map<String, Object> validRequest() {
        Map<String, Object> request = new HashMap<>();
        request.put("name", "2026 校园音乐节");
        request.put("description", "demo");
        request.put("type", "PAID");
        request.put("openTime", "2026-11-01T09:00:00");
        request.put("closeTime", "2026-11-10T18:00:00");
        return request;
    }
}
