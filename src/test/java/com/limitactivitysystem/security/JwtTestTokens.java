package com.limitactivitysystem.security;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;

public final class JwtTestTokens {

    // Test fixture only. Production requires a separately generated random environment key.
    public static final String SECRET_BASE64 = "Zm9yLXVuaXQtdGVzdHMtb25seS1uby1wcm9kdWN0aW9uLXNlY3JldA==";
    public static final String SECRET_PROPERTY = "security.jwt.secret-base64=" + SECRET_BASE64;

    private JwtTestTokens() {
    }

    public static String token(JwtEncoder encoder, long userId, String role) {
        Instant now = Instant.now();
        return token(encoder, JwtClaimsSet.builder()
                .subject(Long.toString(userId))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(1800))
                .claim("role", role)
                .build());
    }

    public static String token(JwtEncoder encoder, JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
