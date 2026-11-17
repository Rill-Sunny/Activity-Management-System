package com.limitactivitysystem.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@Import(JwtConfig.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            BearerTokenResolver bearerTokenResolver,
            AppUserJwtAuthenticationConverter jwtAuthenticationConverter,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            JsonAccessDeniedHandler accessDeniedHandler) throws Exception {

        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .rememberMe(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth

                        // 公开接口
                        .requestMatchers(
                                HttpMethod.POST, "/auth/login", "/auth/register"
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.GET, "/actuator/health"
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.GET, "/activities", "/activities/{id}",
                                "/products/{productId}", "/products/{productId}/skus/{skuId}", "/activities/{activityId}/products",
                                "/sessions/{sessionId}/seat-layout", "/sessions/{sessionId}/cancellation", "/activities/{activityId}/cancellation"
                        ).permitAll()

                        // 管理端点除公开健康检查外均要求认证。
                        .requestMatchers("/actuator/**").authenticated()

                        // Outbox replay can resend durable business events and is limited to administrators.
                        .requestMatchers("/admin/**").hasRole("ADMIN")

                        // 所有发布者管理接口均要求发布者角色
                        .requestMatchers("/publisher/**").hasRole("PUBLISHER")

                        // 普通消费接口允许 USER 和 PUBLISHER 登录后访问
                        .anyRequest().authenticated()
                )

                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(bearerTokenResolver)
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                );

        return http.build();
    }

    @Bean
    public BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver resolver = new DefaultBearerTokenResolver();
        resolver.setBearerTokenHeaderName(HttpHeaders.AUTHORIZATION);
        resolver.setAllowFormEncodedBodyParameter(false);
        resolver.setAllowUriQueryParameter(false);
        return resolver;
    }

    @Bean
    public AppUserJwtAuthenticationConverter jwtAuthenticationConverter(AppUserMapper appUserMapper) {
        return new AppUserJwtAuthenticationConverter(appUserMapper);
    }

    @Bean
    public JsonAuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return new JsonAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    public JsonAccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return new JsonAccessDeniedHandler(objectMapper);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
