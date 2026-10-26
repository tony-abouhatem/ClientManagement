/*
 * Ticket: docs/backlog/02-security-resource-server.md
 *
 * Interfaces under test (do not exist yet):
 *   - com.datum.clientmanagement.security: SecurityFilterChain (stateless, CSRF disabled),
 *     Keycloak realm-role JwtAuthenticationConverter, ProblemDetail AuthenticationEntryPoint and AccessDeniedHandler
 *   - Endpoints: /customers/**, /actuator/health, /v3/api-docs, /swagger-ui/**
 *
 * Tokens: use spring-security-test jwt() with authorities for role checks; AC-2 needs a real JwtDecoder
 * (e.g. locally signed RSA key / NimbusJwtDecoder) to exercise expiry, signature and issuer validation.
 * Expected issuer in tests: whatever ISSUER_URI is in the test profile (referred to below as <issuer>).
 *
 * Authorization-pass checks (AC-4, AC-5) only assert "not 401 and not 403"; the business result
 * (200/201/204/404) belongs to ticket 03.
 */
package com.datum.clientmanagement.functional;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecurityFunctionalTest {

    // AC-1 (ticket 02) / NFR-1
    // Given no Authorization header
    // When each of: GET /customers, GET /customers/1, GET /customers/search?name=ana, GET /customers/1/score,
    //      POST /customers, PUT /customers/1, DELETE /customers/1 is called
    // Then every response is 401
    //  And Content-Type is "application/problem+json"
    //  And body has "status":401 and a non-empty "title"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 401 problem+json when any /customers endpoint is called without a token")
    void ac1_noToken_returns401ProblemDetail() { }

    // AC-2 (ticket 02) / NFR-1
    // Given a JWT signed with the trusted key, issuer <issuer>, role ADMIN, but "exp" = now - 60 s
    // When GET /customers is called with "Authorization: Bearer <that token>"
    // Then the response is 401 with Content-Type "application/problem+json"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 401 when the JWT is expired")
    void ac2_expiredToken_returns401() { }

    // AC-2 (ticket 02) / NFR-1
    // Given a JWT with valid claims (issuer <issuer>, role ADMIN, exp = now + 300 s) but signed with an UNTRUSTED key
    // When GET /customers is called with it
    // Then the response is 401 with Content-Type "application/problem+json"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 401 when the JWT signature is invalid")
    void ac2_badSignature_returns401() { }

    // AC-2 (ticket 02) / NFR-1
    // Given a JWT signed with the trusted key, role ADMIN, exp = now + 300 s, but "iss" = "http://evil.example/realms/other"
    // When GET /customers is called with it
    // Then the response is 401 with Content-Type "application/problem+json"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 401 when the JWT issuer is wrong")
    void ac2_wrongIssuer_returns401() { }

    // AC-3 (ticket 02) / NFR-1
    // Given a JWT with authority ROLE_USER only
    // When POST /customers {"name":"Ana Souza","cpf":"52998224725","email":"ana@example.com"} is called
    // Then the response is 403
    //  And Content-Type is "application/problem+json" with "status":403
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 403 problem+json when a USER calls POST /customers")
    void ac3_userPost_returns403() { }

    // AC-3 (ticket 02) / NFR-1
    // Given a JWT with authority ROLE_USER only
    // When PUT /customers/1 {"name":"Ana Souza","email":"ana@example.com","status":"ACTIVE","version":0} is called
    // Then the response is 403 problem+json ("status":403)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 403 problem+json when a USER calls PUT /customers/{id}")
    void ac3_userPut_returns403() { }

    // AC-3 (ticket 02) / NFR-1
    // Given a JWT with authority ROLE_USER only
    // When DELETE /customers/1 is called
    // Then the response is 403 problem+json ("status":403)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 403 problem+json when a USER calls DELETE /customers/{id}")
    void ac3_userDelete_returns403() { }

    // AC-4 (ticket 02)
    // Given a JWT with authority ROLE_USER only
    // When GET /customers, GET /customers/1, GET /customers/search?name=ana and GET /customers/1/score are called
    // Then no response is 401 or 403
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should pass authorization when a USER calls GET endpoints under /customers")
    void ac4_userGet_isAuthorized() { }

    // AC-5 (ticket 02)
    // Given a JWT with authority ROLE_ADMIN
    // When GET /customers, POST /customers (valid body, cpf 11144477735), PUT /customers/1
    //      {"name":"Ana Souza","email":"ana@example.com","status":"ACTIVE","version":0}
    //      and DELETE /customers/1 are called
    // Then no response is 401 or 403
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should pass authorization when an ADMIN calls GET, POST, PUT or DELETE under /customers")
    void ac5_adminAllMethods_isAuthorized() { }

    // AC-6 (ticket 02) / NFR-2
    // Given no Authorization header
    // When GET /actuator/health, GET /v3/api-docs and GET /swagger-ui/index.html are called
    // Then none of the responses is 401 or 403 (health returns 200; api-docs returns 200)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should allow access without a token when health, api-docs or swagger-ui is called")
    void ac6_publicEndpoints_allowAnonymous() { }

    // Edge case (ticket 02)
    // Given a validly signed JWT with NO "realm_access" claim
    // When GET /customers is called
    // Then the response is 403 problem+json (authenticated but no role)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 403 when the JWT has no realm_access claim")
    void edge_tokenWithoutRealmAccess_returns403() { }

    // Edge case (ticket 02)
    // Given a validly signed JWT with realm_access.roles = ["admin", "user"] (lowercase)
    // When GET /customers and POST /customers are called
    // Then both responses are 403 (only exact "USER"/"ADMIN" grant access)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 403 when role names have the wrong casing")
    void edge_wrongRoleCasing_returns403() { }

    // Edge case (ticket 02)
    // Given no Authorization header
    // When GET /does-not-exist is called
    // Then the response is 401 problem+json (not 404)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 401 instead of 404 when an unknown path is called without a token")
    void edge_unknownPathNoToken_returns401() { }
}
