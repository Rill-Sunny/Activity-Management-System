package com.limitactivitysystem.deployment;

import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/** Public application-port readiness without exposing dependency details or metrics. */
@RestController
@Profile("docker")
public class ContainerHealthController {
    private final HealthEndpoint health;
    public ContainerHealthController(HealthEndpoint health) { this.health = health; }
    @GetMapping("/actuator/health")
    public ResponseEntity<Map<String, String>> health() {
        var component = health.healthForPath("readiness");
        boolean ready = component != null && Status.UP.equals(component.getStatus());
        return ResponseEntity.status(ready ? 200 : 503).body(Map.of("status", ready ? "UP" : "DOWN"));
    }
}
