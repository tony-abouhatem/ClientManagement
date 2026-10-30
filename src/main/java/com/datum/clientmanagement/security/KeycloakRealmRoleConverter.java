package com.datum.clientmanagement.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Maps Keycloak realm roles ({@code realm_access.roles}) to Spring authorities {@code ROLE_<role>}.
 * Role names are kept verbatim so that only the exact {@code USER}/{@code ADMIN} names match the access rules.
 * A token without a usable {@code realm_access.roles} claim is still authenticated, just without roles.
 */
final class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String ROLES_KEY = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        if (!(jwt.getClaim(REALM_ACCESS_CLAIM) instanceof Map<?, ?> realmAccess)) {
            return List.of();
        }
        if (!(realmAccess.get(ROLES_KEY) instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(ROLE_PREFIX + role))
                .toList();
    }
}
