/*
 * Ticket: 05 (GitHub issue #6)
 *
 * Interfaces under test (do not exist yet):
 *   - GET /customers/{id}/score -> 200 ScoreResponse {customerId, cpf, score, classification}
 *   - com.datum.clientmanagement.score.ScoreClient calling external GET {SCORE_BASE_URL}/scores/{cpf}
 *   - Errors as ProblemDetail (application/problem+json)
 *
 * External Score is an in-JVM WireMock server (never the real network); SCORE_BASE_URL points at it.
 * Customer used unless stated: id C, cpf "52998224725". All calls use a USER jwt().
 * Circuit breaker state must be reset between tests (except where a test builds it up).
 * Timing/limit scenarios for NFR-4/NFR-6 live in nonfunctional/ScoreResilienceNfrTest.
 */
package com.datum.clientmanagement.functional;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CustomerScoreFunctionalTest {

    // AC-1 (ticket 05)
    // Given customer C (cpf 52998224725)
    //  And WireMock GET /scores/52998224725 -> 200 {"cpf":"52998224725","score":750,"classification":"GOOD"}
    // When GET /customers/C/score as USER (and again as ADMIN)
    // Then each response is 200 with body {"customerId":C,"cpf":"52998224725","score":750,"classification":"GOOD"}
    //  And WireMock received GET /scores/52998224725 (normalized 11-digit CPF in the URL)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 200 with the score when the customer exists and Score returns 200")
    void scoreOk_returns200() { }

    // Edge case (ticket 05): classification is passed through
    // Given WireMock /scores/52998224725 -> 200 {"cpf":"52998224725","score":300,"classification":"SOMETHING_NEW"}
    // When GET /customers/C/score
    // Then the response is 200 with classification "SOMETHING_NEW"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should pass through an unknown classification when Score returns one")
    void unknownClassification_isPassedThrough() { }

    // AC-2 (ticket 05)
    // Given no customer with id 999999
    // When GET /customers/999999/score
    // Then the response is 404 problem+json with "title":"Customer not found"
    //  And WireMock received 0 requests to /scores/**
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 404 Customer not found without calling Score when the customer does not exist")
    void unknownCustomer_returns404WithoutCallingScore() { }

    // AC-3 (ticket 05) / NFR-5
    // Given WireMock /scores/52998224725 -> 404
    // When GET /customers/C/score
    // Then the response is 404 problem+json with "title":"Score not found"
    //  And WireMock received exactly 1 request (no retry)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 404 Score not found when Score returns 404")
    void scoreNotFound_returns404WithDistinctTitle() { }

    // AC-4 (ticket 05) / NFR-4
    // Given WireMock scenario: 1st GET /scores/52998224725 -> 500, 2nd -> 200 {"cpf":"52998224725","score":610,"classification":"FAIR"}
    // When GET /customers/C/score
    // Then the response is 200 with score 610
    //  And WireMock received exactly 2 requests
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 200 after a retry when Score fails once with 5xx and then succeeds")
    void retryAfter5xx_returns200() { }

    // AC-4 (ticket 05) / NFR-5
    // Given WireMock /scores/52998224725 -> 503 on every call
    // When GET /customers/C/score
    // Then the response is 503 problem+json ("status":503)
    //  And WireMock received exactly 2 requests (max 2 attempts)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 503 when Score returns 5xx on both attempts")
    void serverErrorOnBothAttempts_returns503() { }

    // AC-5 (ticket 05) / NFR-5
    // Given WireMock /scores/52998224725 -> 200 with fixed delay 3000 ms (> read timeout 2 s)
    // When GET /customers/C/score
    // Then the response is 504 problem+json ("status":504)
    //  And total elapsed time is <= 5000 ms
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 504 within 5 seconds when Score responds slower than the read timeout")
    void slowScore_returns504Within5s() { }

    // AC-6 (ticket 05) / NFR-5
    // Given SCORE_BASE_URL points at a closed local port (WireMock stopped / connection refused)
    // When GET /customers/C/score
    // Then the response is 503 problem+json ("status":503)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 503 when Score is unreachable")
    void connectionRefused_returns503() { }

    // AC-7 (ticket 05) / NFR-5
    // Given WireMock /scores/52998224725 -> 200 with body "not json{"   (and, separately,
    //       200 {"cpf":"52998224725","classification":"GOOD"} — missing "score")
    // When GET /customers/C/score
    // Then each response is 502 problem+json ("status":502)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 502 when Score returns a malformed body or one missing score")
    void malformedScoreBody_returns502() { }

    // AC-8 (ticket 05)
    // Given WireMock /scores/52998224725 -> 400 (and, separately, 422)
    // When GET /customers/C/score
    // Then each response is 502 problem+json ("status":502)
    //  And WireMock received exactly 1 request per call (4xx is never retried)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 502 without retry when Score returns a 4xx other than 404")
    void other4xx_returns502WithoutRetry() { }

    // AC-9 (ticket 05) / NFR-6
    // Given WireMock /scores/52998224725 -> 500 always, and the circuit has been opened by 10 failing calls
    // When GET /customers/C/score is called again
    // Then the response is 503 problem+json
    //  And WireMock's request count does not increase
    //  (the < 100 ms latency target is asserted in ScoreResilienceNfrTest)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 503 without calling Score when the circuit is open")
    void circuitOpen_returns503WithoutCallingScore() { }

    // AC-10 (ticket 05)
    // Given the files under docker/wiremock/mappings/
    // When they are parsed
    // Then there are stubs for GET /scores/{cpf} covering: 200 OK, 404, 500 and a delayed response (fixedDelayMilliseconds > 2000)
    //  And each stub's CPF is a valid 11-digit CPF
    //  And each of those CPFs appears in README.md
    // (Specific CPFs are chosen during the ticket 05 implementation — deferred by the user.)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should provide OK, 404, 500 and slow WireMock stubs keyed by documented CPFs")
    void composeWireMockStubs_existForAllScenarios() { }
}
