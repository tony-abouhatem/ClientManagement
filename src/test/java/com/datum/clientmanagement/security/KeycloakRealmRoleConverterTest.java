package com.datum.clientmanagement.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

class KeycloakRealmRoleConverterTest {

    private static final String SUBJECT = "a1b2c3";

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    // AC-7 (ticket 02)
    @Test
    void keycloakAdminRealmRole_givesPrincipalRoleAdminAuthority() {
        Jwt jwt = jwt(claims -> claims.put("realm_access", Map.of("roles", List.of("ADMIN"))));

        AbstractAuthenticationToken authentication = new SecurityConfig().jwtAuthenticationConverter().convert(jwt);

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo(SUBJECT);
        assertThat(authorityNames(authentication.getAuthorities())).contains("ROLE_ADMIN").doesNotContain("ROLE_USER");
    }

    @Test
    void convert_userAndAdminRoles_mapsEachToRolePrefixedAuthority() {
        Jwt jwt = jwt(claims -> claims.put("realm_access", Map.of("roles", List.of("USER", "ADMIN"))));

        assertThat(authorityNames(converter.convert(jwt))).containsExactly("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void convert_lowercaseRoles_keepsNamesVerbatim() {
        Jwt jwt = jwt(claims -> claims.put("realm_access", Map.of("roles", List.of("admin", "user"))));

        assertThat(authorityNames(converter.convert(jwt))).containsExactly("ROLE_admin", "ROLE_user");
    }

    @Test
    void convert_noRealmAccessClaim_returnsNoAuthorities() {
        Jwt jwt = jwt(claims -> claims.put("scope", "openid"));

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void convert_realmAccessWithoutRoles_returnsNoAuthorities() {
        Jwt jwt = jwt(claims -> claims.put("realm_access", Map.of("other", List.of("ADMIN"))));

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void convert_emptyRoles_returnsNoAuthorities() {
        Jwt jwt = jwt(claims -> claims.put("realm_access", Map.of("roles", List.of())));

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void convert_realmAccessNotAnObject_returnsNoAuthorities() {
        Jwt jwt = jwt(claims -> claims.put("realm_access", "ADMIN"));

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void convert_rolesNotAList_returnsNoAuthorities() {
        Jwt jwt = jwt(claims -> claims.put("realm_access", Map.of("roles", "ADMIN")));

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void convert_nonStringRoleEntries_areIgnored() {
        Jwt jwt = jwt(claims -> claims.put("realm_access", Map.of("roles", List.of(42, "USER", Map.of()))));

        assertThat(authorityNames(converter.convert(jwt))).containsExactly("ROLE_USER");
    }

    private static Jwt jwt(Consumer<Map<String, Object>> claimsCustomizer) {
        Instant issuedAt = Instant.parse("2026-01-01T00:00:00Z");
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(SUBJECT)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(300))
                .claims(claimsCustomizer)
                .build();
    }

    private static List<String> authorityNames(Collection<? extends GrantedAuthority> authorities) {
        List<String> names = new ArrayList<>();
        for (GrantedAuthority authority : authorities) {
            names.add(requireNonNull(authority.getAuthority()));
        }
        return names;
    }
}
