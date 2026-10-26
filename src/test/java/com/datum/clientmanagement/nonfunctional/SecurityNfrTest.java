/*
 * NFR-1, NFR-2 (docs/PRODUCT.md); tickets docs/backlog/02-security-resource-server.md, 03, 04.
 *
 * Interfaces under test (do not exist yet): SecurityFilterChain in com.datum.clientmanagement.security,
 * ProblemDetail entry point / access-denied handler, all HTTP endpoints.
 */
package com.datum.clientmanagement.nonfunctional;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecurityNfrTest {

    // NFR-2:security (allow-list)
    // Given no Authorization header
    // When GET /actuator/env, /actuator/beans, /actuator/configprops, /actuator/metrics and /actuator (root) are called
    // Then each response is 401 or 404 — never 200 (only /actuator/health/** is public)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should not expose actuator endpoints other than health when called without a token")
    void nfr2_nonHealthActuatorEndpoints_areNotPublic() { }

    // NFR-2:security
    // Given no Authorization header
    // When GET /actuator/health/liveness and /actuator/health/readiness are called
    // Then each response is not 401/403 (health subpaths are public)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should allow health subpaths without a token")
    void nfr2_healthSubpaths_arePublic() { }

    // NFR-1:security
    // Given no Authorization header
    // When GET /customers is called
    // Then the 401 response has header WWW-Authenticate starting with "Bearer"
    //  And the body is problem+json with no "trace" and no "exception" fields
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return a Bearer challenge and a clean problem body when the token is missing")
    void nfr1_401_hasBearerChallengeAndNoInternals() { }

    // NFR-1:security (stateless)
    // Given an ADMIN request to GET /customers that succeeds
    // When the response is inspected
    // Then there is no Set-Cookie header (no JSESSIONID; stateless session)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should not create an HTTP session when an authenticated request is served")
    void nfr1_noSessionCookie_isIssued() { }

    // NFR:security (injection) — ticket 04
    // Given customers "Ana Souza" and "Bruno Lima"
    // When GET /customers/search?name=' OR '1'='1 and GET /customers?name=%27%3B%20DROP%20TABLE%20customers%3B--
    // Then each response is 200 with empty content
    //  And the customers table still has 2 rows
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should treat SQL injection payloads in name as literal text")
    void nfrSecurity_sqlInjectionInName_isInert() { }

    // NFR:security (information leakage) — ticket 03 / NFR-13
    // Given an ADMIN
    // When requests that produce 400 (malformed JSON), 404 (unknown id), 409 (duplicate CPF) and 500 (forced failure) are made
    // Then no response body contains "Exception", "org.springframework", "org.hibernate", "SQL", "at com.datum" or "trace"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should not leak stack traces, class names or SQL in any error response")
    void nfrSecurity_errorResponses_doNotLeakInternals() { }
}
