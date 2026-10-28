package com.limitactivitysystem.security;

import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.identity.controller.UserController;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.List;
import java.util.Map;


// Spring Security 测试支持
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

// MockMvc HTTP 请求构建
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

// MockMvc 响应断言
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;


@WebMvcTest(controllers = {UserController.class, SecurityAccessTest.TestController.class},
        properties = JwtTestTokens.SECRET_PROPERTY)
@Import({SecurityConfig.class, CurrentUserProvider.class, SecurityAccessTest.TestController.class})
class SecurityAccessTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private AppUserMapper appUserMapper;

    private AppUser buyer;

    @BeforeEach
    void setUpUsers() {
        buyer = new AppUser();
        buyer.setId(1L);
        buyer.setUsername("feng");
        buyer.setPasswordHash("stored-password-hash");
        given(appUserMapper.findById(1L)).willReturn(buyer);

        AppUser publisher = new AppUser();
        publisher.setId(2L);
        publisher.setUsername("host");
        publisher.setRole("PUBLISHER");
        given(appUserMapper.findById(2L)).willReturn(publisher);
    }

    @Test
    void anonymousCannotReadProfile() throws Exception {
        mvc.perform(get("/users/me"))
                .andExpect(unauthorized());
    }

    @Test
    void buyerCanReadProfile() throws Exception {
        mvc.perform(get("/users/me")
                        .header(HttpHeaders.AUTHORIZATION, authorization("USER")))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "{\"id\":1,\"username\":\"feng\",\"role\":\"USER\",\"status\":\"ACTIVE\"}",
                        JsonCompareMode.STRICT));
    }

    @Test
    void publisherCanReadOwnProfile() throws Exception {
        mvc.perform(get("/users/me")
                        .header(HttpHeaders.AUTHORIZATION, authorization("PUBLISHER")))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "{\"id\":2,\"username\":\"host\",\"role\":\"PUBLISHER\",\"status\":\"ACTIVE\"}",
                        JsonCompareMode.STRICT));
    }

    @Test
    void profileIdentityCannotBeOverriddenByRequestParameters() throws Exception {
        mvc.perform(get("/users/me")
                        .queryParam("id", "2")
                        .queryParam("userId", "2")
                        .header(HttpHeaders.AUTHORIZATION, authorization("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.username").value("feng"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void buyerCannotPublish() throws Exception {
        mvc.perform(post("/publisher/activities")
                        .header(HttpHeaders.AUTHORIZATION, authorization("USER")))
                .andExpect(forbidden());
    }

    @Test
    void publisherCanPublish() throws Exception {
        mvc.perform(post("/publisher/activities")
                        .header(HttpHeaders.AUTHORIZATION, authorization("PUBLISHER")))
                .andExpect(status().isCreated());
    }

    @Test
    void basicCredentialsCannotAuthenticate() throws Exception {
        mvc.perform(get("/users/me").with(httpBasic("buyer", "demoPass123!")))
                .andExpect(unauthorized());
    }

    @Test
    void anonymousCanReadHealth() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auth/register", "/auth/login"})
    void anonymousCanPostToAuthenticationEndpoints(String path) throws Exception {
        mvc.perform(post(path))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousCannotReadBusinessEndpoints() throws Exception {
        mvc.perform(get("/private-business"))
                .andExpect(unauthorized());
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /publisher/activities/1",
            "PATCH, /publisher/activities/1",
            "DELETE, /publisher/activities/1",
            "POST, /publisher/activities/1/sessions"
    })
    void publisherRoutesRequirePublisherForEveryMethod(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path)
                        .header(HttpHeaders.AUTHORIZATION, authorization("USER")))
                .andExpect(forbidden());

        mvc.perform(request(HttpMethod.valueOf(method), path)
                        .header(HttpHeaders.AUTHORIZATION, authorization("PUBLISHER")))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({
            "USER, /reservations",
            "PUBLISHER, /reservations",
            "USER, /ticket-orders",
            "PUBLISHER, /ticket-orders",
            "USER, /product-orders",
            "PUBLISHER, /product-orders"
    })
    void bothRolesCanUseConsumerEndpoints(String role, String path) throws Exception {
        mvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION, authorization(role)))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/reservations", "/ticket-orders", "/product-orders"})
    void anonymousCannotUseConsumerEndpoints(String path) throws Exception {
        mvc.perform(post(path))
                .andExpect(unauthorized());
    }

    @Test
    void bearerAuthenticationDoesNotCreateOrPersistSession() throws Exception {
        mvc.perform(get("/users/me")
                        .header(HttpHeaders.AUTHORIZATION, authorization("USER")))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());

        mvc.perform(get("/users/me"))
                .andExpect(unauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
    }

    @Test
    void ignoresAuthenticationStoredInExistingSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken("publisher", null,
                List.of(new SimpleGrantedAuthority("ROLE_PUBLISHER"))));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        mvc.perform(get("/users/me")
                        .session(session)
                        .cookie(new Cookie("JSESSIONID", session.getId())))
                .andExpect(unauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"accessToken", "access_token", "JWT"})
    void cookiesCannotProvideAuthentication(String cookieName) throws Exception {
        mvc.perform(get("/users/me")
                        .cookie(new Cookie(cookieName, JwtTestTokens.token(jwtEncoder, 1L, "USER"))))
                .andExpect(unauthorized());
    }

    @Test
    void queryParameterCannotProvideAuthentication() throws Exception {
        mvc.perform(get("/users/me")
                        .queryParam("access_token", JwtTestTokens.token(jwtEncoder, 1L, "USER")))
                .andExpect(unauthorized());
    }

    @Test
    void formParameterCannotProvideAuthentication() throws Exception {
        mvc.perform(post("/reservations")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("access_token", JwtTestTokens.token(jwtEncoder, 1L, "USER")))
                .andExpect(unauthorized());
    }

    @Test
    void headerAloneDeterminesIdentity() throws Exception {
        String publisherToken = JwtTestTokens.token(jwtEncoder, 2L, "PUBLISHER");
        mvc.perform(get("/users/me")
                        .header(HttpHeaders.AUTHORIZATION, authorization("USER"))
                        .cookie(new Cookie("access_token", publisherToken))
                        .queryParam("access_token", publisherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void formLoginCannotAuthenticate() throws Exception {
        mvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "buyer")
                        .param("password", "demoPass123!"))
                .andExpect(unauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void rejectsExpiredTokens() throws Exception {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("1")
                .issuedAt(now.minusSeconds(7200))
                .expiresAt(now.minusSeconds(3600))
                .claim("role", "USER").build();

        mvc.perform(get("/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestTokens.token(jwtEncoder, claims)))
                .andExpect(unauthorized());
    }

    @Test
    void rejectsTokensSignedWithAnotherKey() throws Exception {
        JwtEncoder anotherEncoder = new JwtConfig().jwtEncoder(new SecretKeySpec(new byte[32], "HmacSHA256"));
        mvc.perform(get("/users/me")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + JwtTestTokens.token(anotherEncoder, 1L, "USER")))
                .andExpect(unauthorized());
    }

    @Test
    void rejectsMalformedTokens() throws Exception {
        mvc.perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(unauthorized());
    }

    @Test
    void rejectsTokensWithoutExpiry() throws Exception {
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("1").issuedAt(Instant.now())
                .claim("role", "USER").build();

        mvc.perform(get("/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestTokens.token(jwtEncoder, claims)))
                .andExpect(unauthorized());
    }

    @Test
    void rejectsTokensAfterAccountIsDisabled() throws Exception {
        String token = authorization("USER");
        buyer.setStatus("DISABLED");

        mvc.perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(unauthorized());
    }

    @Test
    void rejectsTokensForMissingUsers() throws Exception {
        mvc.perform(get("/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestTokens.token(jwtEncoder, 3L, "USER")))
                .andExpect(unauthorized());
    }

    @Test
    void rejectsRolesThatDoNotMatchCurrentUser() throws Exception {
        mvc.perform(get("/publisher/activities/1")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + JwtTestTokens.token(jwtEncoder, 1L, "PUBLISHER")))
                .andExpect(unauthorized());
    }

    @Test
    void providerRejectsUserDisabledAfterRequestAuthentication() throws Exception {
        AppUser disabledUser = new AppUser();
        disabledUser.setId(1L);
        disabledUser.setStatus("DISABLED");
        given(appUserMapper.findById(1L)).willReturn(buyer, disabledUser);

        mvc.perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, authorization("USER")))
                .andExpect(unauthorized());
    }

    @Test
    void providerRejectsUserRemovedAfterRequestAuthentication() throws Exception {
        given(appUserMapper.findById(1L)).willReturn(buyer, (AppUser) null);

        mvc.perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, authorization("USER")))
                .andExpect(unauthorized());
    }

    @Test
    void unauthorizedResponseIsJsonEvenWhenHtmlIsRequested() throws Exception {
        mvc.perform(get("/users/me").accept(MediaType.TEXT_HTML))
                .andExpect(unauthorized());
    }

    @Test
    void forbiddenResponseIsJsonEvenWhenHtmlIsRequested() throws Exception {
        mvc.perform(post("/publisher/activities")
                        .header(HttpHeaders.AUTHORIZATION, authorization("USER"))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(forbidden());
    }

    private static ResultMatcher unauthorized() {
        return result -> {
            status().isUnauthorized().match(result);
            content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON).match(result);
            content().encoding("UTF-8").match(result);
            content().json("{\"code\":\"UNAUTHORIZED\",\"message\":\"Authentication is required\"}",
                    JsonCompareMode.STRICT).match(result);
            header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer").match(result);
        };
    }

    private static ResultMatcher forbidden() {
        return result -> {
            status().isForbidden().match(result);
            content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON).match(result);
            content().encoding("UTF-8").match(result);
            content().json("{\"code\":\"FORBIDDEN\",\"message\":\"Insufficient permission\"}",
                    JsonCompareMode.STRICT).match(result);
        };
    }

    private String authorization(String role) {
        long userId = "PUBLISHER".equals(role) ? 2L : 1L;
        return "Bearer " + JwtTestTokens.token(jwtEncoder, userId, role);
    }

    @RestController
    static class TestController {

        private final CurrentUserProvider currentUserProvider;

        TestController(CurrentUserProvider currentUserProvider) {
            this.currentUserProvider = currentUserProvider;
        }

        @GetMapping("/private-business")
        Map<String, String> currentUser() {
            return Map.of("userId", currentUserProvider.getCurrentUserId().toString());
        }

        @GetMapping("/actuator/health")
        Map<String, String> health() {
            return Map.of("status", "UP");
        }

        @PostMapping({"/auth/register", "/auth/login"})
        void authenticationEndpoint() {
        }

        @PostMapping({"/reservations", "/ticket-orders", "/product-orders"})
        @ResponseStatus(HttpStatus.CREATED)
        void createConsumerOrder() {
        }

        @RequestMapping(
                path = {"/publisher/activities/1", "/publisher/activities/1/sessions"},
                method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PATCH, RequestMethod.DELETE}
        )
        void manageActivity() {
        }

        @PostMapping("/publisher/activities")
        @ResponseStatus(HttpStatus.CREATED)
        void publishActivity() {
        }
    }
}
