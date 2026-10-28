package com.limitactivitysystem.identity.controller;

import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.JwtTestTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Uses the configured MySQL schema. Fixtures are rolled back and Flyway does not change the database.
@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class UsersMeMySqlIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AppUserMapper appUserMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void registrationPersistsBcryptAndLoginTokenReachesProfileThroughRealMySql() throws Exception {
        String username = "auth_it_" + UUID.randomUUID().toString().replace("-", "");
        String rawPassword = "integration-test-password";
        String credentials = objectMapper.writeValueAsString(
                Map.of("username", username, "password", rawPassword));
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isCreated());

        AppUser stored = appUserMapper.findByUsername(username);
        assertThat(stored).isNotNull();
        assertThat(stored.getId()).isPositive();
        String databaseHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM app_user WHERE id = ?", String.class, stored.getId());
        assertThat(databaseHash).isEqualTo(stored.getPasswordHash())
                .isNotEqualTo(rawPassword).matches("\\$2[aby]\\$.*");
        assertThat(passwordEncoder.matches(rawPassword, databaseHash)).isTrue();
        assertThat(stored.getRole()).isEqualTo("USER");
        assertThat(stored.getStatus()).isEqualTo("ACTIVE");

        String loginBody = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(loginBody).path("accessToken").asText();
        mvc.perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(Map.of(
                        "id", stored.getId(), "username", username, "role", "USER", "status", "ACTIVE")),
                        JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "PUBLISHER"})
    void readsCurrentUserThroughRealMyBatisAndMySql(String role) throws Exception {
        AppUser user = insertUser(role);
        String expected = "{\"id\":%d,\"username\":\"%s\",\"role\":\"%s\",\"status\":\"ACTIVE\"}"
                .formatted(user.getId(), user.getUsername(), role);

        mvc.perform(get("/users/me")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + JwtTestTokens.token(jwtEncoder, user.getId(), role)))
                .andExpect(status().isOk())
                .andExpect(content().json(expected, JsonCompareMode.STRICT));
    }

    @Test
    void rejectsOldTokenAfterUserIsDisabledInMySql() throws Exception {
        AppUser user = insertUser("USER");
        String token = JwtTestTokens.token(jwtEncoder, user.getId(), "USER");
        assertThat(jdbcTemplate.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", user.getId()))
                .isEqualTo(1);

        mvc.perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(
                        "{\"code\":\"UNAUTHORIZED\",\"message\":\"Authentication is required\"}",
                        JsonCompareMode.STRICT));
    }

    private AppUser insertUser(String role) {
        AppUser user = new AppUser();
        user.setUsername("me_it_" + UUID.randomUUID().toString().replace("-", ""));
        user.setPasswordHash(passwordEncoder.encode("integration-test-password"));
        user.setRole(role);
        assertThat(appUserMapper.insert(user)).isEqualTo(1);
        assertThat(user.getId()).isPositive();
        return user;
    }
}
