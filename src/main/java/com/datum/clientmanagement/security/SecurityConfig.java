package com.datum.clientmanagement.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Stateless OAuth2 resource server (NFR-1, NFR-2): only health and the API docs are public, USER reads customers,
 * ADMIN reads and writes them, and every 401/403 is a ProblemDetail.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final String ROLE_USER = "USER";
    private static final String ROLE_ADMIN = "ADMIN";

    private final String[] PUBLIC_ENDPOINTS =
            {"/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"};
    private static final String CUSTOMER_ENDPOINTS = "/customers/**";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter) {
        ProblemDetailResponseWriter problemWriter = new ProblemDetailResponseWriter();
        AuthenticationEntryPoint entryPoint = new ProblemDetailAuthenticationEntryPoint(problemWriter);
        AccessDeniedHandler accessDeniedHandler = new ProblemDetailAccessDeniedHandler(problemWriter);
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.GET, CUSTOMER_ENDPOINTS).hasAnyRole(ROLE_USER, ROLE_ADMIN)
                        .requestMatchers(CUSTOMER_ENDPOINTS).hasRole(ROLE_ADMIN)
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }
}
