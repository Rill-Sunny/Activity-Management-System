package com.limitactivitysystem.security;

import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

public class AppUserJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AppUserMapper appUserMapper;

    public AppUserJwtAuthenticationConverter(AppUserMapper appUserMapper) {
        this.appUserMapper = appUserMapper;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Long userId;
        try {
            userId = Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new InvalidBearerTokenException("Invalid bearer token");
        }
        if (userId <= 0) {
            throw new InvalidBearerTokenException("Invalid bearer token");
        }

        AppUser user = appUserMapper.findById(userId);
        Object role = jwt.getClaims().get("role");
        if (user == null || !"ACTIVE".equals(user.getStatus())
                || !("USER".equals(role) || "PUBLISHER".equals(role))
                || !role.equals(user.getRole())) {
            throw new InvalidBearerTokenException("Invalid bearer token");
        }

        return new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)), jwt.getSubject());
    }
}
