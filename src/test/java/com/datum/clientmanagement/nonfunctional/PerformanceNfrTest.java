/*
 * NFR-15 (docs/PRODUCT.md); tickets 03 and 04 (GitHub issues #4, #5).
 *
 * PRODUCT.md says this target is "measured informally; no load-test tooling required". These scenarios are a
 * lightweight in-JVM check (sequential requests, p95 computed in the test). Suggested: tag them so they can be
 * excluded on slow machines — see the flagged assumption in the report.
 */
package com.datum.clientmanagement.nonfunctional;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PerformanceNfrTest {

    // NFR-15:performance
    // Given 10 000 customers seeded in Testcontainers Postgres (about half named with accents, e.g. "João N")
    //  And 20 warm-up requests already made
    // When 200 sequential GET /customers?page=<random 0..499>&size=20 requests and
    //      200 sequential GET /customers/search?name=joao requests are made as USER
    // Then the p95 latency of each series is < 300 ms
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should keep list and search p95 under 300 ms when there are 10k customers")
    void nfr15_listAndSearchP95Under300ms() { }

    // NFR-15:performance
    // Given 10 000 customers seeded
    // When 200 sequential GET /customers/{random id}, 100 POST /customers (unique valid CPFs) and 100 PUT requests are made
    // Then the p95 latency of each operation type is < 300 ms
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should keep CRUD p95 under 300 ms when there are 10k customers")
    void nfr15_crudP95Under300ms() { }
}
