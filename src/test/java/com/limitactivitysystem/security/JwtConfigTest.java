package com.limitactivitysystem.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class JwtConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(JwtConfig.class);

    @Test
    void missingKeyPreventsStartup() {
        contextRunner.run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-valid-base64!", "c2hvcnQ="})
    void invalidKeysPreventStartup(String encodedKey) {
        contextRunner.withPropertyValues("security.jwt.secret-base64=" + encodedKey)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    void validKeyCreatesWorkingEncoderAndDecoder() {
        contextRunner.withPropertyValues(JwtTestTokens.SECRET_PROPERTY)
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(JwtEncoder.class)
                            .hasSingleBean(JwtDecoder.class);
                    String token = JwtTestTokens.token(context.getBean(JwtEncoder.class), 42L, "USER");
                    Jwt jwt = context.getBean(JwtDecoder.class).decode(token);
                    assertThat(jwt.getSubject()).isEqualTo("42");
                    assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
                    assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
                });
    }
}
