/*
 * NFR-4, NFR-5, NFR-6 (docs/PRODUCT.md); ticket 05 (GitHub issue #6).
 *
 * Interfaces under test (do not exist yet): GET /customers/{id}/score, com.datum.clientmanagement.customer.ScoreClient
 * (RestClient + Resilience4j retry "score" and circuit breaker "score"), typed Score @ConfigurationProperties.
 * External Score = in-JVM WireMock. Customer C has cpf "52998224725". Circuit breaker reset between tests.
 */
package com.datum.clientmanagement.nonfunctional;

import com.datum.clientmanagement.ScoreWireMockConfiguration;
import com.datum.clientmanagement.TestcontainersConfiguration;
import com.datum.clientmanagement.config.ScoreProperties;
import com.datum.clientmanagement.customer.CustomerFormats;
import com.datum.clientmanagement.customer.ScoreClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The open-state wait is shortened from 10 s so the recovery scenario does not slow the suite down.
@SpringBootTest(properties = "resilience4j.circuitbreaker.instances.score.wait-duration-in-open-state="
        + ScoreResilienceNfrTest.TEST_OPEN_STATE_WAIT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ScoreWireMockConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"}) // IDE has no database; the SQL runs against Testcontainers Postgres
class ScoreResilienceNfrTest {

    static final String TEST_OPEN_STATE_WAIT = "1s";

    private static final String CPF = "52998224725";
    private static final String SCORE_URL = "/scores/" + CPF;
    private static final String OK_BODY = "{\"cpf\":\"52998224725\",\"score\":750,\"classification\":\"GOOD\"}";
    private static final int NEVER_ANSWERS_DELAY_MS = 30_000;
    private static final int UNDER_READ_TIMEOUT_DELAY_MS = 1500;
    private static final int OVER_READ_TIMEOUT_DELAY_MS = 3000;
    private static final long MAX_SCORE_RESPONSE_MS = 5000;
    private static final long MAX_OPEN_CIRCUIT_RESPONSE_MS = 100;
    private static final int CIRCUIT_WINDOW_CALLS = 10;
    private static final int FAILING_CALLS_BELOW_THRESHOLD = 4;
    private static final String NON_ROUTABLE_SCORE_URL = "http://10.255.255.1:81";
    private static final String INSERT_CUSTOMER = "INSERT INTO customers"
            + " (name, name_normalized, cpf, email, status, created_at, updated_at)"
            + " VALUES ('Ana Souza', 'ana souza', ?, 'ana@example.com', 'ACTIVE', now(), now()) RETURNING id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WireMockServer scoreWireMock;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RetryRegistry retryRegistry;

    @Autowired
    private ScoreProperties scoreProperties;

    @Value("${resilience4j.retry.instances.score.wait-duration}")
    private Duration initialBackoff;

    @Value("${resilience4j.circuitbreaker.instances.score.wait-duration-in-open-state}")
    private Duration openStateWait;

    // Only used to route calls to unreachable addresses; every other test reaches the real bean.
    @MockitoSpyBean
    private ScoreClient scoreClient;

    private Long customerId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM customers");
        customerId = jdbcTemplate.queryForObject(INSERT_CUSTOMER, Long.class, CPF);
        scoreWireMock.resetAll();
        circuitBreaker().reset();
    }

    // NFR-4:resilience
    // Given WireMock /scores/52998224725 never answers (fixed delay 30 000 ms)
    // When GET /customers/C/score is called
    // Then the response arrives in <= 5000 ms with status 504
    //  And WireMock received exactly 2 requests
    @Test
    @DisplayName("should respond within 5 seconds with 504 when Score never answers")
    void scoreNeverAnswers_respondsWithin5s() throws Exception {
        stubScore(okJson(OK_BODY).withFixedDelay(NEVER_ANSWERS_DELAY_MS));

        long elapsedMs = timedMillis(() -> expectProblem(requestScore(), 504));

        assertThat(elapsedMs).isLessThanOrEqualTo(MAX_SCORE_RESPONSE_MS);
        scoreWireMock.verify(2, getRequestedFor(urlEqualTo(SCORE_URL)));
    }

    // NFR-4:resilience (read timeout)
    // Given WireMock /scores/52998224725 -> 200 with delay 1500 ms (below the 2 s read timeout)
    // When GET /customers/C/score
    // Then the response is 200 and WireMock received exactly 1 request
    @Test
    @DisplayName("should succeed without retry when Score answers just under the read timeout")
    void responseUnderReadTimeout_succeeds() throws Exception {
        stubScore(okJson(OK_BODY).withFixedDelay(UNDER_READ_TIMEOUT_DELAY_MS));

        requestScore().andExpect(status().isOk());

        scoreWireMock.verify(1, getRequestedFor(urlEqualTo(SCORE_URL)));
    }

    // NFR-4:resilience (connect timeout)
    // Given SCORE_BASE_URL points at a non-routable address (e.g. http://10.255.255.1:81) so the TCP connect hangs
    // When GET /customers/C/score
    // Then the response arrives in <= 5000 ms with status 503 problem+json ("status":503)
    //      (connect timeout counts as "unavailable", like connection refused; only read timeout maps to 504)
    @Test
    @DisplayName("should return 503 within 5 seconds when the TCP connect to Score times out")
    void connectTimeout_givesUpWithin5s() throws Exception {
        routeScoreCallsTo(NON_ROUTABLE_SCORE_URL);

        long elapsedMs = timedMillis(() -> expectProblem(requestScore(), 503));

        assertThat(elapsedMs).isLessThanOrEqualTo(MAX_SCORE_RESPONSE_MS);
    }

    // NFR-4:resilience (retry only on 5xx/timeouts)
    // Given WireMock /scores/52998224725 -> 404, then separately -> 400, then -> 200 with malformed body
    // When GET /customers/C/score in each case
    // Then WireMock received exactly 1 request in each case (no retry)
    @Test
    @DisplayName("should not retry when Score returns 4xx or a malformed 200")
    void nonRetryableResponses_areNotRetried() throws Exception {
        List<ResponseDefinitionBuilder> responses = List.of(
                aResponse().withStatus(404),
                aResponse().withStatus(400),
                malformedOk());

        for (ResponseDefinitionBuilder response : responses) {
            scoreWireMock.resetAll();
            stubScore(response);

            requestScore();

            scoreWireMock.verify(1, getRequestedFor(urlEqualTo(SCORE_URL)));
        }
    }

    // NFR-4:resilience (backoff)
    // Given WireMock /scores/52998224725 -> 500 always, recording request timestamps
    // When GET /customers/C/score
    // Then WireMock received exactly 2 requests and the gap between them is > 0 ms and equals at least the
    //      configured initial backoff
    @Test
    @DisplayName("should wait the configured backoff between the two attempts when Score returns 5xx")
    void retryUsesBackoff() throws Exception {
        stubScore(aResponse().withStatus(500));

        requestScore();

        List<Long> requestTimes = scoreWireMock.getAllServeEvents().stream()
                .map(ServeEvent::getRequest)
                .map(request -> request.getLoggedDate().getTime())
                .sorted(Comparator.naturalOrder())
                .toList();
        assertThat(requestTimes).hasSize(2);
        long gapMs = requestTimes.get(1) - requestTimes.get(0);
        assertThat(gapMs).isPositive().isGreaterThanOrEqualTo(initialBackoff.toMillis());
    }

    // NFR-5:resilience (status mapping, consolidated)
    // Given WireMock configured for each case in turn
    // When GET /customers/C/score
    // Then: connection refused -> 503; connect timeout -> 503; 500 twice -> 503; circuit open -> 503;
    //       3 s response delay (read timeout) -> 504;
    //       404 -> 404 title "Score not found"; malformed 200 -> 502; and every error is application/problem+json
    @Test
    @DisplayName("should map each Score failure mode to its documented status as a ProblemDetail")
    void failureModes_mapToDocumentedStatuses() throws Exception {
        routeScoreCallsTo("http://localhost:" + closedPort());
        expectProblem(requestScore(), 503);

        routeScoreCallsTo(NON_ROUTABLE_SCORE_URL);
        expectProblem(requestScore(), 503);
        reset(scoreClient);

        stubScore(aResponse().withStatus(500));
        expectProblem(requestScore(), 503);

        circuitBreaker().transitionToOpenState();
        expectProblem(requestScore(), 503);
        circuitBreaker().reset();

        stubScore(okJson(OK_BODY).withFixedDelay(OVER_READ_TIMEOUT_DELAY_MS));
        expectProblem(requestScore(), 504);

        stubScore(aResponse().withStatus(404));
        expectProblem(requestScore(), 404).andExpect(jsonPath("$.title").value("Score not found"));

        stubScore(malformedOk());
        expectProblem(requestScore(), 502);
    }

    // NFR-6:resilience
    // Given WireMock /scores/52998224725 -> 500 always and a fresh (CLOSED) circuit
    // When GET /customers/C/score is called 10 times
    // Then the circuit breaker "score" is OPEN (>= 50% failures over the last 10 calls)
    // When it is called once more
    // Then the response is 503 in < 100 ms and WireMock's request count did not increase
    @Test
    @DisplayName("should open the circuit after 50% failures over 10 calls and then fail fast under 100 ms")
    void circuitOpensAndFailsFast() throws Exception {
        stubScore(aResponse().withStatus(500));
        assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        callScore(CIRCUIT_WINDOW_CALLS);

        assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int requestsBefore = scoreWireMock.getAllServeEvents().size();
        long elapsedMs = timedMillis(() -> expectProblem(requestScore(), 503));
        assertThat(elapsedMs).isLessThan(MAX_OPEN_CIRCUIT_RESPONSE_MS);
        assertThat(scoreWireMock.getAllServeEvents()).hasSize(requestsBefore);
    }

    // NFR-6:resilience
    // Given 10 calls of which 4 fail (WireMock 500) and 6 succeed (WireMock 200)
    // When the 11th call is made
    // Then the circuit is still CLOSED and the call reaches WireMock
    @Test
    @DisplayName("should keep the circuit closed when failures are below 50%")
    void belowThreshold_staysClosed() throws Exception {
        stubScore(aResponse().withStatus(500));
        callScore(FAILING_CALLS_BELOW_THRESHOLD);
        stubScore(okJson(OK_BODY));
        callScore(CIRCUIT_WINDOW_CALLS - FAILING_CALLS_BELOW_THRESHOLD);
        int requestsBefore = scoreWireMock.getAllServeEvents().size();

        requestScore().andExpect(status().isOk());

        assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(scoreWireMock.getAllServeEvents()).hasSize(requestsBefore + 1);
    }

    // NFR-6:resilience (recovery; open duration 10 s per ARCHITECTURE.md)
    // Given the circuit is OPEN and WireMock now returns 200
    // When the open-state wait (10 s, or a shortened test value) elapses and GET /customers/C/score is called
    // Then the response is 200 and the circuit transitions towards CLOSED
    @Test
    @DisplayName("should let calls through again when the circuit open period has elapsed and Score recovered")
    void circuitRecoversAfterWaitDuration() throws Exception {
        stubScore(aResponse().withStatus(500));
        callScore(CIRCUIT_WINDOW_CALLS);
        assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        stubScore(okJson(OK_BODY));

        Thread.sleep(openStateWait.plusMillis(200).toMillis());

        requestScore().andExpect(status().isOk());
        assertThat(circuitBreaker().getState())
                .isIn(CircuitBreaker.State.HALF_OPEN, CircuitBreaker.State.CLOSED);
    }

    @FunctionalInterface
    private interface Request {
        void run() throws Exception;
    }

    private static long timedMillis(Request request) throws Exception {
        long start = System.nanoTime();
        request.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    private void callScore(int times) throws Exception {
        for (int call = 0; call < times; call++) {
            requestScore();
        }
    }

    /** Sends the endpoint's Score calls to another address, through a real client sharing retry and breaker. */
    private void routeScoreCallsTo(String baseUrl) {
        ScoreClient redirected = new ScoreClient(new ScoreProperties(URI.create(baseUrl),
                scoreProperties.connectTimeout(), scoreProperties.readTimeout()), retryRegistry, circuitBreakerRegistry,
                new CustomerFormats());
        doAnswer(invocation -> redirected.fetch(invocation.getArgument(0))).when(scoreClient).fetch(anyString());
    }

    private CircuitBreaker circuitBreaker() {
        return circuitBreakerRegistry.circuitBreaker(ScoreClient.RESILIENCE_INSTANCE);
    }

    private void stubScore(ResponseDefinitionBuilder response) {
        scoreWireMock.stubFor(WireMock.get(SCORE_URL).willReturn(response));
    }

    private static ResponseDefinitionBuilder malformedOk() {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("not json{");
    }

    private ResultActions requestScore() throws Exception {
        return mockMvc.perform(get("/customers/" + customerId + "/score")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private static ResultActions expectProblem(ResultActions result, int expectedStatus) throws Exception {
        return result
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expectedStatus));
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
