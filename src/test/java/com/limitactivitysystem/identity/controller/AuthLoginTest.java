package com.limitactivitysystem.identity.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.identity.service.AuthService;
import com.limitactivitysystem.security.CurrentUserProvider;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.security.JwtTokenService;
import com.limitactivitysystem.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Only persistence is mocked; password hashing, JWT signing/decoding and the filter chain are real.
@WebMvcTest(controllers = {AuthController.class, UserController.class},
        properties = JwtTestTokens.SECRET_PROPERTY)
@Import({SecurityConfig.class, AuthService.class, JwtTokenService.class, CurrentUserProvider.class})
class AuthLoginTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtDecoder jwtDecoder;
    @MockitoBean private AppUserMapper appUserMapper;

    private AppUser user;

    @BeforeEach
    void setUpUser() {
        user = new AppUser();
        user.setId(1L);
        user.setUsername("feng");
        user.setPasswordHash(passwordEncoder.encode("12345678"));
        given(appUserMapper.findByUsername("feng")).willReturn(user);
        given(appUserMapper.findById(1L)).willReturn(user);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "PUBLISHER"})
    void correctPasswordIssuesThirtyMinuteJwtThatCanAccessProfile(String role) throws Exception {
        user.setRole(role);
        Instant beforeLogin = Instant.now().minusSeconds(1);
        String body = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("feng", "12345678")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(body);
        assertThat(response.size()).isEqualTo(3);
        assertThat(response.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(response.path("expiresIn").asLong()).isEqualTo(1800);
        String token = response.path("accessToken").asText();
        assertThat(token).isNotBlank();
        Jwt jwt = jwtDecoder.decode(token);
        assertThat(jwt.getSubject()).isEqualTo("1");
        assertThat(jwt.getClaimAsString("role")).isEqualTo(role);
        assertThat(jwt.getIssuedAt()).isBetween(beforeLogin, Instant.now());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(30));
        assertThat(jwt.getClaims()).containsOnlyKeys("sub", "iat", "exp", "role");

        mvc.perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"id":1,"username":"feng","role":"%s","status":"ACTIVE"}
                        """.formatted(role), JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"incorrect-password", "x"})
    void wrongPasswordReturnsUniform401(String wrongPassword) throws Exception {
        expectLoginUnauthorized("feng", wrongPassword);
    }

    @Test
    void nonexistentUserReturnsSame401() throws Exception {
        expectLoginUnauthorized("missing", "12345678");
    }

    @Test
    void disabledUserCannotLoginEvenWithCorrectPassword() throws Exception {
        user.setStatus("DISABLED");
        expectLoginUnauthorized("feng", "12345678");
    }

    @Test
    void loginRejectsExtraPrivilegeFields() throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"feng","password":"12345678","role":"PUBLISHER"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginRejectsPasswordOverBcryptByteLimit() throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("feng", "密".repeat(25))))
                .andExpect(status().isBadRequest());
    }

    private void expectLoginUnauthorized(String username, String password) throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, password)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().json("""
                        {"code":"UNAUTHORIZED","message":"Authentication is required"}
                        """, JsonCompareMode.STRICT));
    }

    private String credentials(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("username", username, "password", password));
    }
}
