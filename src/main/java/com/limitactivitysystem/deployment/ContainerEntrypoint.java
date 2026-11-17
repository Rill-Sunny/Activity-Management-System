package com.limitactivitysystem.deployment;

import com.limitactivitysystem.LimitActivitySystemApplication;
import org.flywaydb.core.Flyway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/** Container-only commands: migration never starts web servers, listeners or scheduled jobs. */
public final class ContainerEntrypoint {
    private ContainerEntrypoint() {}

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "run" : args[0];
        if (mode.equals("health")) {
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/actuator/health"))
                        .timeout(Duration.ofSeconds(3)).GET().build();
                if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() != 200) {
                    throw new IllegalStateException("Application health is not UP");
                }
            }
            return;
        }
        Map<String, String> env = System.getenv();
        if (mode.equals("migrate")) {
            Flyway.configure().dataSource(required(env, "SPRING_DATASOURCE_URL"),
                            required(env, "SPRING_DATASOURCE_USERNAME"), required(env, "SPRING_DATASOURCE_PASSWORD"))
                    .locations("classpath:db/migration").baselineOnMigrate(false)
                    .validateOnMigrate(true).cleanDisabled(true).load().migrate();
        } else if (mode.equals("run")) {
            validate(env);
            LimitActivitySystemApplication.main(new String[0]);
        } else {
            throw new IllegalArgumentException("Expected run, migrate or health");
        }
    }

    static void validate(Map<String, String> env) {
        for (String key : new String[]{"SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME",
                "SPRING_DATASOURCE_PASSWORD", "RABBITMQ_USERNAME", "RABBITMQ_PASSWORD",
                "JWT_SECRET_BASE64", "INSTANCE_ID", "METRICS_PASSWORD"}) required(env, key);
        byte[] secret;
        try { secret = Base64.getDecoder().decode(env.get("JWT_SECRET_BASE64")); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("JWT_SECRET_BASE64 must be Base64"); }
        if (secret.length < 32) throw new IllegalArgumentException("JWT_SECRET_BASE64 requires at least 32 random bytes");
        if (!env.getOrDefault("SPRING_PROFILES_ACTIVE", "").matches("(^|.*,)(docker)(,.*|$)")) {
            throw new IllegalArgumentException("Container requires the docker profile");
        }
    }

    static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank() || value.toUpperCase(java.util.Locale.ROOT).contains("REPLACE_")) {
            throw new IllegalArgumentException("Missing or placeholder configuration: " + key);
        }
        return value;
    }
}
