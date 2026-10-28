package com.limitactivitysystem.activity.controller;

import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.service.ActivityQueryService;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Real query service and Security filter chain; persistence is mocked.
@WebMvcTest(controllers = ActivityQueryController.class, properties = JwtTestTokens.SECRET_PROPERTY)
@Import({SecurityConfig.class, ActivityQueryService.class})
class ActivityQueryTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtEncoder jwtEncoder;
    @MockitoBean private ActivityMapper activityMapper;
    @MockitoBean private AppUserMapper userMapper;

    private Activity activity;

    @BeforeEach
    void setUp() {
        activity = new Activity();
        activity.setActivityId(10L);
        activity.setPublisherId(2L);
        activity.setName("2026 校园音乐节");
        activity.setDescription("demo");
        activity.setType("PAID");
        activity.setStatus("PUBLISHED");
        activity.setOpenTime(LocalDateTime.of(2099, 11, 1, 9, 0));
        activity.setCloseTime(LocalDateTime.of(2099, 11, 10, 18, 0));
        given(activityMapper.findPublished()).willReturn(List.of(activity));
        given(activityMapper.findPublishedById(10L)).willReturn(activity);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FREE", "PAID"})
    void anonymousCanListAndReadPublishedActivitiesBeforeOpening(String type) throws Exception {
        activity.setType(type);
        String expected = """
                {"activityId":10,"name":"2026 校园音乐节","description":"demo","type":"%s",
                 "status":"PUBLISHED","openTime":"2099-11-01T09:00:00","closeTime":"2099-11-10T18:00:00"}
                """.formatted(type);
        mvc.perform(get("/activities"))
                .andExpect(status().isOk())
                .andExpect(content().json("[" + expected + "]", JsonCompareMode.STRICT));
        mvc.perform(get("/activities/10"))
                .andExpect(status().isOk())
                .andExpect(content().json(expected, JsonCompareMode.STRICT));
        verifyNoInteractions(userMapper);
        verify(activityMapper, never()).findById(anyLong());
        verify(activityMapper, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void publishedActivitiesRemainVisibleAfterTheSaleWindowCloses() throws Exception {
        activity.setOpenTime(LocalDateTime.of(2000, 11, 1, 9, 0));
        activity.setCloseTime(LocalDateTime.of(2000, 11, 10, 18, 0));
        mvc.perform(get("/activities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].activityId").value(10));
        mvc.perform(get("/activities/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityId").value(10));
    }

    @Test
    void noPublishedActivitiesReturnsAnEmptyArray() throws Exception {
        given(activityMapper.findPublished()).willReturn(List.of());
        mvc.perform(get("/activities"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]", JsonCompareMode.STRICT));
    }

    @Test
    void queryParametersCannotOverridePublishedVisibility() throws Exception {
        mvc.perform(get("/activities").queryParam("status", "DRAFT").queryParam("publisherId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("PUBLISHED"));
        verify(activityMapper).findPublished();
        verify(activityMapper, never()).findById(anyLong());
    }

    @Test
    void missingOrUnpublishedActivityReturns404() throws Exception {
        mvc.perform(get("/activities/999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Activity not found"));
        verify(activityMapper).findPublishedById(999L);
        verify(activityMapper, never()).findById(anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "PUBLISHER"})
    void bothRolesCanUseTheSamePublicQueries(String role) throws Exception {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setRole(role);
        given(userMapper.findById(1L)).willReturn(user);
        String bearer = "Bearer " + JwtTestTokens.token(jwtEncoder, 1L, role);
        mvc.perform(get("/activities").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());
        mvc.perform(get("/activities/10").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());
    }

    @Test
    void malformedBearerIsStillRejectedOnPublicQueries() throws Exception {
        mvc.perform(get("/activities").header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(activityMapper);
    }

    @Test
    void invalidActivityIdReturns400BeforeTheMapper() throws Exception {
        mvc.perform(get("/activities/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ACTIVITY_REQUEST"));
        verifyNoInteractions(activityMapper);
    }

    @ParameterizedTest
    @CsvSource({"POST,/activities", "PUT,/activities/10", "PATCH,/activities/10", "DELETE,/activities/10",
            "GET,/activities/10/sessions", "GET,/users/me"})
    void anonymousAccessIsLimitedToTheTwoReadEndpoints(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(activityMapper);
    }
}
