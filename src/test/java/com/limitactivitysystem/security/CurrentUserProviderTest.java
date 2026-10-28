package com.limitactivitysystem.security;

import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CurrentUserProviderTest {

    @Mock
    private AppUserMapper appUserMapper;

    @InjectMocks
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void clearInitialContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "PUBLISHER"})
    void returnsActiveUserForEitherRole(String role) {
        authenticate("42", role);
        AppUser user = activeUser();
        user.setRole(role);
        given(appUserMapper.findById(42L)).willReturn(user);

        assertThat(currentUserProvider.getCurrentActiveUser()).isSameAs(user);
        assertThat(currentUserProvider.getCurrentUserId()).isEqualTo(42L);
        assertThat(currentUserProvider.getAuthenticatedUserId()).isEqualTo(42L);
        verify(appUserMapper, times(2)).findById(42L);
    }

    @Test
    void readsSubjectInsteadOfAuthenticationDisplayName() {
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt("42", "USER"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "999");
        SecurityContextHolder.getContext().setAuthentication(authentication);
        given(appUserMapper.findById(42L)).willReturn(activeUser());

        assertThat(currentUserProvider.getCurrentUserId()).isEqualTo(42L);
        verify(appUserMapper).findById(42L);
    }

    @Test
    void rejectsMissingAuthentication() {
        assertThatThrownBy(currentUserProvider::getCurrentActiveUser)
                .isInstanceOf(AuthenticationException.class);
        assertThatThrownBy(currentUserProvider::getAuthenticatedUserId).isInstanceOf(AuthenticationException.class);
        verifyNoInteractions(appUserMapper);
    }

    @Test
    void rejectsAnonymousAuthenticationEvenThoughItsAuthenticatedFlagIsTrue() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "test-key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThatThrownBy(currentUserProvider::getCurrentUserId)
                .isInstanceOf(AuthenticationException.class);
        verifyNoInteractions(appUserMapper);
    }

    @Test
    void rejectsNonJwtAuthentication() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "42", null, AuthorityUtils.createAuthorityList("ROLE_USER")));

        assertThatThrownBy(currentUserProvider::getCurrentActiveUser)
                .isInstanceOf(AuthenticationException.class);
        verifyNoInteractions(appUserMapper);
    }

    @Test
    void rejectsUnauthenticatedJwt() {
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt("42", "USER"));
        authentication.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThatThrownBy(currentUserProvider::getCurrentUserId)
                .isInstanceOf(AuthenticationException.class);
        verifyNoInteractions(appUserMapper);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"feng", "0", "-1", "42.0", "9223372036854775808"})
    void rejectsInvalidSubjects(String subject) {
        authenticate(subject, "USER");

        assertThatThrownBy(currentUserProvider::getCurrentUserId)
                .isInstanceOf(AuthenticationException.class);
        assertThatThrownBy(currentUserProvider::getAuthenticatedUserId).isInstanceOf(AuthenticationException.class);
        verifyNoInteractions(appUserMapper);
    }

    @Test
    void authenticatedIdAloneDoesNotAuthorizeAnActiveAccount() {
        authenticate("42", "USER");
        assertThat(currentUserProvider.getAuthenticatedUserId()).isEqualTo(42L);
        verifyNoInteractions(appUserMapper);
    }

    @Test
    void rejectsDeletedUser() {
        authenticate("42", "USER");

        assertThatThrownBy(currentUserProvider::getCurrentActiveUser)
                .isInstanceOf(AuthenticationException.class);
        verify(appUserMapper).findById(42L);
    }

    @Test
    void idAccessorAlsoRejectsDisabledUser() {
        authenticate("42", "USER");
        AppUser user = activeUser();
        user.setStatus("DISABLED");
        given(appUserMapper.findById(42L)).willReturn(user);

        assertThatThrownBy(currentUserProvider::getCurrentUserId)
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void checksDatabaseAgainWithoutChangingJwt() {
        authenticate("42", "USER");
        AppUser disabledUser = activeUser();
        disabledUser.setStatus("DISABLED");
        given(appUserMapper.findById(42L)).willReturn(activeUser(), disabledUser);

        assertThat(currentUserProvider.getCurrentUserId()).isEqualTo(42L);
        assertThatThrownBy(currentUserProvider::getCurrentActiveUser)
                .isInstanceOf(AuthenticationException.class);
        verify(appUserMapper, times(2)).findById(42L);
    }

    private void authenticate(String subject, String role) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt(subject, role), AuthorityUtils.createAuthorityList("ROLE_" + role)));
    }

    private Jwt jwt(String subject, String role) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(1800))
                .claim("role", role)
                .build();
    }

    private AppUser activeUser() {
        AppUser user = new AppUser();
        user.setId(42L);
        return user;
    }
}
