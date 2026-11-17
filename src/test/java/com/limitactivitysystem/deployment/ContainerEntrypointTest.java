package com.limitactivitysystem.deployment;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class ContainerEntrypointTest {
    private Map<String, String> environment() {
        return new HashMap<>(Map.of("SPRING_DATASOURCE_URL", "jdbc:mysql://mysql/test",
                "SPRING_DATASOURCE_USERNAME", "app", "SPRING_DATASOURCE_PASSWORD", "test-database-secret",
                "RABBITMQ_USERNAME", "app", "RABBITMQ_PASSWORD", "test-rabbit-secret",
                "JWT_SECRET_BASE64", Base64.getEncoder().encodeToString(new byte[32]),
                "INSTANCE_ID", "app-a", "METRICS_PASSWORD", "test-metrics-secret", "SPRING_PROFILES_ACTIVE", "docker"));
    }
    @Test void missingConfigurationNeverFallsBackToLocalCredentials() {
        for (String key : environment().keySet()) {
            var env = environment(); env.remove(key);
            assertThatThrownBy(() -> ContainerEntrypoint.validate(env)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void placeholderAndShortJwtFailWithoutExposingSecret() {
        var env = environment(); env.put("RABBITMQ_PASSWORD", "REPLACE_ME-private");
        assertThatThrownBy(() -> ContainerEntrypoint.validate(env)).hasMessageContaining("RABBITMQ_PASSWORD")
                .hasMessageNotContaining("REPLACE_ME-private");
        var shortKey = environment(); shortKey.put("JWT_SECRET_BASE64", "dGVzdA==");
        assertThatThrownBy(() -> ContainerEntrypoint.validate(shortKey)).hasMessageContaining("32 random bytes");
    }
    @Test void validConfigurationIsAccepted() { assertThatCode(() -> ContainerEntrypoint.validate(environment())).doesNotThrowAnyException(); }
}
