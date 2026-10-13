package com.fleet.storefront.adapter.in.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.storefront.application.port.in.AuthenticateTerminalQuery;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Stateless API-key security (ADR 0010). Authorization is by device role, declared per endpoint here in
 * one place, mirroring docs/API.md. No sessions, no cookies (so no CSRF surface); TLS is terminated in
 * front of the node (reverse proxy) — see docs/OPERATIONS.md.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    private static final String POS = "POS";
    private static final String KITCHEN = "KITCHEN";
    private static final String MANAGER = "MANAGER";
    private static final String HQ = "HQ";
    private static final String MONITOR = "MONITOR";

    @Bean
    SecurityFilterChain api(HttpSecurity http, AuthenticateTerminalQuery authenticate, ObjectMapper json)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyAuthenticationFilter(authenticate, json), AuthorizationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> ProblemResponses.write(res, json,
                                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Missing API key (Authorization: Bearer …)"))
                        .accessDeniedHandler((req, res, ex) -> ProblemResponses.write(res, json,
                                HttpStatus.FORBIDDEN, "FORBIDDEN", "This device's role may not use this endpoint")))
                .authorizeHttpRequests(a -> a
                        // Error/async re-dispatches of an already authorized request (e.g. the KDS stream).
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/prometheus").hasAnyRole(MONITOR, MANAGER)
                        .requestMatchers("/actuator/**").hasRole(MANAGER)

                        .requestMatchers(HttpMethod.GET, "/api/v1/me").authenticated()

                        .requestMatchers(HttpMethod.POST, "/api/v1/orders/*/refunds").hasRole(MANAGER)
                        .requestMatchers("/api/v1/orders", "/api/v1/orders/**").hasAnyRole(POS, MANAGER)

                        .requestMatchers(HttpMethod.GET, "/api/v1/catalog").hasAnyRole(POS, KITCHEN, MANAGER)
                        .requestMatchers("/api/v1/catalog/**").hasRole(MANAGER)
                        .requestMatchers("/api/v1/inventory/**").hasRole(MANAGER)

                        .requestMatchers("/api/v1/kitchen/**").hasAnyRole(KITCHEN, MANAGER)

                        .requestMatchers("/api/v1/terminals", "/api/v1/terminals/**").hasRole(MANAGER)
                        .requestMatchers("/api/v1/ops/**").hasRole(MANAGER)
                        .requestMatchers("/api/v1/reports/**").hasRole(MANAGER)
                        .requestMatchers("/api/v1/sync/**").hasAnyRole(HQ, MANAGER)

                        // Unknown API paths: authenticated callers get a 404 rather than a hint.
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll());
        return http.build();
    }
}
