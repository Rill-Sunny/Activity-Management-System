package com.limitactivitysystem.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.JwtTestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Real Actuator endpoints, MVC controllers and Security filters. Only external persistence is mocked.
@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@AutoConfigureObservability
class ObservabilityTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtEncoder jwtEncoder;
    @MockitoBean private DataSource dataSource;
    @MockitoBean private AppUserMapper appUserMapper;

    private Connection connection;
    private AppUser user;
    private Logger requestLogger;
    private ListAppender<ILoggingEvent> requestLogs;

    @BeforeEach
    void setUp() throws Exception {
        connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        given(dataSource.getConnection()).willReturn(connection);
        given(connection.getMetaData()).willReturn(metadata);
        given(metadata.getDatabaseProductName()).willReturn("MySQL");
        given(connection.isValid(anyInt())).willReturn(true);

        user = new AppUser();
        user.setId(1L);
        user.setUsername("feng");
        user.setPasswordHash(passwordEncoder.encode("private-password-123"));
        given(appUserMapper.findById(1L)).willReturn(user);
        given(appUserMapper.findByUsername("feng")).willReturn(user);

        requestLogger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        requestLogs = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        requestLogs.start();
        requestLogger.addAppender(requestLogs);
    }

    @AfterEach
    void tearDown() {
        requestLogger.detachAppender(requestLogs);
        requestLogs.stop();
        MDC.remove("requestId");
    }

    @Test
    void publicHealthChecksTheDataSourceWithoutExposingDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", JsonCompareMode.STRICT));
        verify(dataSource, atLeastOnce()).getConnection();
        verify(connection).isValid(0);
    }

    @Test
    void invalidDatabaseConnectionMakesHealthDown() throws Exception {
        given(connection.isValid(anyInt())).willReturn(false);
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"status\":\"DOWN\"}", JsonCompareMode.STRICT));
    }

    @Test
    void unavailableDatabaseConnectionMakesHealthDown() throws Exception {
        given(dataSource.getConnection()).willThrow(new SQLException("Connection unavailable"));
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"status\":\"DOWN\"}", JsonCompareMode.STRICT));
        verify(dataSource, atLeastOnce()).getConnection();
    }

    @Test
    void metricsRequireAuthentication() throws Exception {
        for (String path : new String[]{"/actuator/metrics", "/actuator/metrics/jvm.memory.used"}) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().json("""
                            {"code":"UNAUTHORIZED","message":"Authentication is required"}
                            """, JsonCompareMode.STRICT));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "PUBLISHER"})
    void authenticatedUsersCanReadRealJvmAndHttpMetrics(String role) throws Exception {
        user.setRole(role);
        String authorization = "Bearer " + JwtTestTokens.token(jwtEncoder, 1L, role);
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names", hasItems("jvm.memory.used", "http.server.requests")));
        mvc.perform(get("/actuator/metrics/http.server.requests")
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("http.server.requests"))
                .andExpect(jsonPath("$.measurements").isNotEmpty());
        mvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isNotFound());
    }

    @Test
    void accessLogsCoverSuccess401And403WithoutRecordingSecrets() throws Exception {
        String clientRequestId = "untrusted-client-id";
        MvcResult login = mvc.perform(post("/auth/login")
                        .header("X-Request-ID", clientRequestId)
                        .queryParam("password", "query-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "feng", "password", "private-password-123"))))
                .andExpect(status().isOk()).andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString())
                .path("accessToken").asText();
        MvcResult profile = mvc.perform(get("/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        MvcResult anonymous = mvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized()).andReturn();
        MvcResult forbidden = mvc.perform(post("/publisher/activities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden()).andReturn();

        assertThat(requestLogs.list).hasSize(4);
        MvcResult[] results = {login, profile, anonymous, forbidden};
        for (int i = 0; i < results.length; i++) {
            MvcResult result = results[i];
            String requestId = result.getResponse().getHeader("X-Request-ID");
            assertThat(requestId).isNotNull().isNotEqualTo(clientRequestId);
            assertThat(UUID.fromString(requestId).toString()).isEqualTo(requestId);
            ILoggingEvent event = requestLogs.list.get(i);
            assertThat(event.getMDCPropertyMap()).containsEntry("requestId", requestId);
            assertThat(event.getMDCPropertyMap().get("traceId")).matches("[0-9a-f]{32}");
            assertThat(event.getMDCPropertyMap().get("spanId")).matches("[0-9a-f]{16}");
            assertThat(event.getFormattedMessage())
                    .startsWith("HTTP request method=" + result.getRequest().getMethod()
                            + " path=" + result.getRequest().getRequestURI()
                            + " status=" + result.getResponse().getStatus() + " durationMs=")
                    .matches(".*durationMs=\\d+$")
                    .doesNotContain("private-password-123", user.getPasswordHash(), token,
                            JwtTestTokens.SECRET_BASE64, "query-secret", "passwordHash", clientRequestId);
        }
        assertThat(results).extracting(result -> result.getResponse().getHeader("X-Request-ID"))
                .doesNotHaveDuplicates();
        assertThat(MDC.get("requestId")).isNull();
    }
}
