package com.limitactivitysystem.security;

import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserProvider {

    private final AppUserMapper appUserMapper;

    public CurrentUserProvider(AppUserMapper appUserMapper) {
        this.appUserMapper = appUserMapper;
    }

    public Long getCurrentUserId() {
        return getCurrentActiveUser().getId();
    }

    public AppUser getCurrentActiveUser() {
        Long userId = getAuthenticatedUserId();
        // Query on each call; an account may have been disabled since JWT authentication.
        AppUser user = appUserMapper.findById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new InvalidBearerTokenException("Invalid bearer token");
        }
        return user;
    }

    /** Principal ID only. Internal transaction callers must check ACTIVE under their account lock. */
    public Long getAuthenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !jwtAuthentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("Authenticated JWT is required");
        }

        Long userId;
        try {
            userId = Long.valueOf(jwtAuthentication.getToken().getSubject());
        } catch (NumberFormatException exception) {
            throw new InvalidBearerTokenException("Invalid bearer token");
        }
        if (userId <= 0) {
            throw new InvalidBearerTokenException("Invalid bearer token");
        }

        return userId;
    }
}
