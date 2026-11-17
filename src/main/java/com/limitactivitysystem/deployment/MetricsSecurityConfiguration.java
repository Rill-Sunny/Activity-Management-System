package com.limitactivitysystem.deployment;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Prometheus uses its own internal port and credential, never a user's JWT. */
@Configuration(proxyBeanMethods = false)
@Profile("docker")
public class MetricsSecurityConfiguration {
    @Bean
    @Order(0)
    SecurityFilterChain metricsSecurity(HttpSecurity http,
            @Value("${management.server.port}") int managementPort,
            @Value("${METRICS_PASSWORD}") String password) throws Exception {
        var users = new InMemoryUserDetailsManager(User.withUsername("prometheus")
                .password(new BCryptPasswordEncoder().encode(password)).roles("METRICS").build());
        return http.securityMatcher(request -> request.getRequestURI().equals("/actuator/prometheus")
                        || request.getRequestURI().equals("/actuator/metrics")
                        || request.getRequestURI().startsWith("/actuator/metrics/"))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .userDetailsService(users)
                .authorizeHttpRequests(a -> a.anyRequest().access((authentication, context) ->
                        new org.springframework.security.authorization.AuthorizationDecision(
                                context.getRequest().getLocalPort() == managementPort
                                && authentication.get().getAuthorities().stream()
                                .anyMatch(role -> role.getAuthority().equals("ROLE_METRICS")))))
                .httpBasic(b -> b.authenticationEntryPoint((request, response, failure) ->
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)))
                .build();
    }
}
