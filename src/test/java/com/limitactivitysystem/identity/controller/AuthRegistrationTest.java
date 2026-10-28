package com.limitactivitysystem.identity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.identity.service.AuthService;
import com.limitactivitysystem.security.SecurityConfig;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.security.JwtTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class, properties = JwtTestTokens.SECRET_PROPERTY)
@Import({SecurityConfig.class, AuthService.class, JwtTokenService.class})
@ExtendWith(OutputCaptureExtension.class)
class AuthRegistrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private AppUserMapper appUserMapper;

    @Test
    void anonymousRegistrationStoresBcryptHashWithFixedRoleAndStatus() throws Exception {
        stubSuccessfulInsert();

        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("feng", "12345678")))
                .andExpect(status().isCreated())
                .andExpect(content().json("{\"id\":42,\"username\":\"feng\"}", JsonCompareMode.STRICT));

        ArgumentCaptor<AppUser> savedUser = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserMapper).insert(savedUser.capture());
        AppUser user = savedUser.getValue();
        assertThat(user.getRole()).isEqualTo("USER");
        assertThat(user.getStatus()).isEqualTo("ACTIVE");
        assertThat(user.getPasswordHash()).matches("\\$2[aby]\\$.*").isNotEqualTo("12345678");
        assertThat(passwordEncoder.matches("12345678", user.getPasswordHash())).isTrue();
    }

    @Test
    void identicalPasswordsGetDifferentSaltedHashes() throws Exception {
        stubSuccessfulInsert();

        for (String username : new String[]{"feng", "other"}) {
            mvc.perform(post("/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request(username, "12345678")))
                    .andExpect(status().isCreated());
        }

        ArgumentCaptor<AppUser> savedUsers = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserMapper, times(2)).insert(savedUsers.capture());
        assertThat(savedUsers.getAllValues().get(0).getPasswordHash())
                .isNotEqualTo(savedUsers.getAllValues().get(1).getPasswordHash());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "role", "status", "passwordHash", "createdAt", "extra"})
    void rejectsUnexpectedFields(String field) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("username", "feng");
        body.put("password", "12345678");
        body.put(field, "PUBLISHER");

        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(appUserMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "role", "status", "passwordHash"})
    void rejectsForbiddenFieldsEvenWhenNull(String field) throws Exception {
        String body = "{\"username\":\"feng\",\"password\":\"12345678\",\"%s\":null}".formatted(field);

        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(appUserMapper);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidRequestsBeforeDatabaseAccess(String body) throws Exception {
        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(appUserMapper);
    }

    static Stream<String> invalidRequests() {
        return Stream.of(
                "{}",
                "null",
                "{",
                "{\"username\":\"feng\"}",
                "{\"password\":\"12345678\"}",
                "{\"username\":null,\"password\":\"12345678\"}",
                "{\"username\":\"feng\",\"password\":null}",
                "{\"username\":\"   \",\"password\":\"12345678\"}",
                "{\"username\":\"%s\",\"password\":\"12345678\"}".formatted("u".repeat(51)),
                "{\"username\":\"feng\",\"password\":\"\"}",
                "{\"username\":\"feng\",\"password\":\"        \"}",
                "{\"username\":\"feng\",\"password\":\"1234567\"}",
                "{\"username\":\"feng\",\"password\":\"%s\"}".formatted("a".repeat(73)),
                "{\"username\":\"feng\",\"password\":\"%s\"}".formatted("汉".repeat(25))
        );
    }

    @ParameterizedTest
    @MethodSource("boundaryPasswords")
    void acceptsCredentialsAtLengthLimits(String password) throws Exception {
        stubSuccessfulInsert();
        String username = "u".repeat(50);

        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(username, password)))
                .andExpect(status().isCreated())
                .andExpect(content().json("{\"id\":42,\"username\":\"%s\"}".formatted(username),
                        JsonCompareMode.STRICT));

        ArgumentCaptor<AppUser> savedUser = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserMapper).insert(savedUser.capture());
        assertThat(passwordEncoder.matches(password, savedUser.getValue().getPasswordHash())).isTrue();
    }

    static Stream<String> boundaryPasswords() {
        return Stream.of("a".repeat(72), "汉".repeat(24));
    }

    @Test
    void returnsConflictForExistingUsername() throws Exception {
        given(appUserMapper.findByUsername("feng")).willReturn(new AppUser());

        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("feng", "12345678")))
                .andExpect(status().isConflict());

        verify(appUserMapper, never()).insert(any(AppUser.class));
    }

    @Test
    void returnsConflictWhenConcurrentInsertHitsUniqueConstraint() throws Exception {
        given(appUserMapper.insert(any(AppUser.class))).willThrow(new DuplicateKeyException("Duplicate username"));

        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("feng", "12345678")))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidPasswordIsNotEchoedOrLogged(CapturedOutput output) throws Exception {
        String password = "short!";

        String response = mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("feng", password)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(password);
        assertThat(output.getAll()).doesNotContain(password);
        verifyNoInteractions(appUserMapper);
    }

    @Test
    void registrationDoesNotSupportOtherHttpMethods() throws Exception {
        mvc.perform(get("/auth/register"))
                .andExpect(status().isUnauthorized());

        AppUser user = new AppUser();
        user.setId(42L);
        given(appUserMapper.findById(42L)).willReturn(user);

        mvc.perform(get("/auth/register")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestTokens.token(jwtEncoder, 42L, "USER")))
                .andExpect(status().isMethodNotAllowed());

        verify(appUserMapper).findById(42L);
        verify(appUserMapper, never()).insert(any(AppUser.class));
    }

    private String request(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("username", username, "password", password));
    }

    private void stubSuccessfulInsert() {
        AtomicLong nextId = new AtomicLong(42);
        given(appUserMapper.insert(any(AppUser.class))).willAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            user.setId(nextId.getAndIncrement());
            return 1;
        });
    }
}
