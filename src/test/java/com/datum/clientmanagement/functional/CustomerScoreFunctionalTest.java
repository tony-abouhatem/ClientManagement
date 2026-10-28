/*
 * Ticket: 05 (GitHub issue #6)
 *
 * Interfaces under test (do not exist yet):
 *   - GET /customers/{id}/score -> 200 ScoreResponse {customerId, cpf, score, classification}
 *   - com.datum.clientmanagement.customer.ScoreClient calling external GET {SCORE_BASE_URL}/scores/{cpf}
 *   - Errors as ProblemDetail (application/problem+json)
 *
 * External Score is an in-JVM WireMock server (never the real network); SCORE_BASE_URL points at it.
 * Customer used unless stated: id C, cpf "52998224725". All calls use a USER jwt().
 * Circuit breaker state must be reset between tests (except where a test builds it up).
 * Timing/limit scenarios for NFR-4/NFR-6 live in nonfunctional/ScoreResilienceNfrTest.
 */
package com.datum.clientmanagement.functional;

import com.datum.clientmanagement.ScoreWireMockConfiguration;
import com.datum.clientmanagement.TestcontainersConfiguration;
import com.datum.clientmanagement.config.ScoreProperties;
import com.datum.clientmanagement.customer.Cpf;
import com.datum.clientmanagement.customer.ScoreClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ScoreWireMockConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"}) // IDE has no database; the SQL runs against Testcontainers Postgres
class CustomerScoreFunctionalTest {

    private static final String CPF = "52998224725";
    private static final String SCORE_URL = "/scores/" + CPF;
    private static final long UNKNOWN_ID = 999999L;
    private static final int READ_TIMEOUT_EXCEEDING_DELAY_MS = 3000;
    private static final long MAX_SCORE_RESPONSE_MS = 5000;
    private static final int CIRCUIT_WINDOW_CALLS = 10;
    private static final Path WIREMOCK_MAPPINGS = Path.of("docker", "wiremock", "mappings");
    private static final Path README = Path.of("README.md");
    private static final Pattern SCORE_STUB_URL = Pattern.compile("/scores/(\\d{11})");
    private static final int SLOW_STUB_MIN_DELAY_MS = 2000;
    private static final String INSERT_CUSTOMER = "INSERT INTO customers"
            + " (name, name_normalized, cpf, email, status, created_at, updated_at)"
            + " VALUES ('Ana Souza', 'ana souza', ?, 'ana@example.com', 'ACTIVE', now(), now()) RETURNING id";

    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    // Only used to route a call to an unreachable address (AC-6); every other test reaches the real bean.
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

    // AC-1 (ticket 05)
    // Given customer C (cpf 52998224725)
    //  And WireMock GET /scores/52998224725 -> 200 {"cpf":"52998224725","score":750,"classification":"GOOD"}
    // When GET /customers/C/score as USER (and again as ADMIN)
    // Then each response is 200 with body {"customerId":C,"cpf":"52998224725","score":750,"classification":"GOOD"}
    //  And WireMock received GET /scores/52998224725 (normalized 11-digit CPF in the URL)
    @Test
    @DisplayName("should return 200 with the score when the customer exists and Score returns 200")
    void scoreOk_returns200() throws Exception {
        stubScore(okJson("{\"cpf\":\"52998224725\",\"score\":750,\"classification\":\"GOOD\"}"));
        JsonNode expected = JSON.readTree("{\"customerId\":" + customerId
                + ",\"cpf\":\"52998224725\",\"score\":750,\"classification\":\"GOOD\"}");

        for (RequestPostProcessor role : List.of(user(), admin())) {
            String body = mockMvc.perform(get(scorePath(customerId)).with(role))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(JSON.readTree(body)).isEqualTo(expected);
        }
        scoreWireMock.verify(2, getRequestedFor(urlEqualTo(SCORE_URL)));
    }

    // Edge case (ticket 05): classification is passed through
    // Given WireMock /scores/52998224725 -> 200 {"cpf":"52998224725","score":300,"classification":"SOMETHING_NEW"}
    // When GET /customers/C/score
    // Then the response is 200 with classification "SOMETHING_NEW"
    @Test
    @DisplayName("should pass through an unknown classification when Score returns one")
    void unknownClassification_isPassedThrough() throws Exception {
        stubScore(okJson("{\"cpf\":\"52998224725\",\"score\":300,\"classification\":\"SOMETHING_NEW\"}"));

        requestScore(customerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classification").value("SOMETHING_NEW"));
    }

    // AC-2 (ticket 05)
    // Given no customer with id 999999
    // When GET /customers/999999/score
    // Then the response is 404 problem+json with the standard "title":"Not Found", same as ticket 03
    //  (user decision, ticket 05: replaces "Customer not found", consistent with the ticket 04 decision)
    //  And WireMock received 0 requests to /scores/**
    @Test
    @DisplayName("should return 404 Not Found without calling Score when the customer does not exist")
    void unknownCustomer_returns404WithoutCallingScore() throws Exception {
        stubScore(okJson("{\"cpf\":\"52998224725\",\"score\":750,\"classification\":\"GOOD\"}"));

        JsonNode problem = expectProblem(requestScore(UNKNOWN_ID), 404);

        assertThat(problem.path("title").asString()).isEqualTo("Not Found");
        scoreWireMock.verify(0, getRequestedFor(urlPathMatching("/scores/.*")));
    }

    // AC-3 (ticket 05) / NFR-5
    // Given WireMock /scores/52998224725 -> 404
    // When GET /customers/C/score
    // Then the response is 404 problem+json with "title":"Score not found"
    //  And WireMock received exactly 1 request (no retry)
    @Test
    @DisplayName("should return 404 Score not found when Score returns 404")
    void scoreNotFound_returns404WithDistinctTitle() throws Exception {
        stubScore(aResponse().withStatus(404));

        JsonNode problem = expectProblem(requestScore(customerId), 404);

        assertThat(problem.path("title").asString()).isEqualTo("Score not found");
        scoreWireMock.verify(1, getRequestedFor(urlEqualTo(SCORE_URL)));
    }

    // AC-4 (ticket 05) / NFR-4
    // Given WireMock scenario: 1st GET /scores/52998224725 -> 500, 2nd -> 200 {"cpf":"52998224725","score":610,"classification":"FAIR"}
    // When GET /customers/C/score
    // Then the response is 200 with score 610
    //  And WireMock received exactly 2 requests
    @Test
    @DisplayName("should return 200 after a retry when Score fails once with 5xx and then succeeds")
    void retryAfter5xx_returns200() throws Exception {
        scoreWireMock.stubFor(WireMock.get(SCORE_URL)
                .inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500)).willSetStateTo("recovered"));
        scoreWireMock.stubFor(WireMock.get(SCORE_URL)
                .inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(okJson("{\"cpf\":\"52998224725\",\"score\":610,\"classification\":\"FAIR\"}")));

        requestScore(customerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(610));
        scoreWireMock.verify(2, getRequestedFor(urlEqualTo(SCORE_URL)));
    }

    // AC-4 (ticket 05) / NFR-5
    // Given WireMock /scores/52998224725 -> 503 on every call
    // When GET /customers/C/score
    // Then the response is 503 problem+json ("status":503)
    //  And WireMock received exactly 2 requests (max 2 attempts)
    @Test
    @DisplayName("should return 503 when Score returns 5xx on both attempts")
    void serverErrorOnBothAttempts_returns503() throws Exception {
        stubScore(aResponse().withStatus(503));

        expectProblem(requestScore(customerId), 503);

        scoreWireMock.verify(2, getRequestedFor(urlEqualTo(SCORE_URL)));
    }

    // AC-5 (ticket 05) / NFR-5
    // Given WireMock /scores/52998224725 -> 200 with fixed delay 3000 ms (> read timeout 2 s)
    // When GET /customers/C/score
    // Then the response is 504 problem+json ("status":504)
    //  And total elapsed time is <= 5000 ms
    @Test
    @DisplayName("should return 504 within 5 seconds when Score responds slower than the read timeout")
    void slowScore_returns504Within5s() throws Exception {
        stubScore(okJson("{\"cpf\":\"52998224725\",\"score\":750,\"classification\":\"GOOD\"}")
                .withFixedDelay(READ_TIMEOUT_EXCEEDING_DELAY_MS));

        long start = System.nanoTime();
        expectProblem(requestScore(customerId), 504);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThanOrEqualTo(MAX_SCORE_RESPONSE_MS);
    }

    // AC-6 (ticket 05) / NFR-5
    // Given SCORE_BASE_URL points at a closed local port (WireMock stopped / connection refused)
    // When GET /customers/C/score
    // Then the response is 503 problem+json ("status":503)
    @Test
    @DisplayName("should return 503 when Score is unreachable")
    void connectionRefused_returns503() throws Exception {
        ScoreClient unreachable = new ScoreClient(
                new ScoreProperties(URI.create("http://localhost:" + closedPort()),
                        scoreProperties.connectTimeout(), scoreProperties.readTimeout()),
                retryRegistry, circuitBreakerRegistry);
        doAnswer(invocation -> unreachable.fetch(invocation.getArgument(0))).when(scoreClient).fetch(anyString());

        expectProblem(requestScore(customerId), 503);
    }

    // AC-7 (ticket 05) / NFR-5
    // Given WireMock /scores/52998224725 -> 200 with body "not json{"   (and, separately,
    //       200 {"cpf":"52998224725","classification":"GOOD"} — missing "score")
    // When GET /customers/C/score
    // Then each response is 502 problem+json ("status":502)
    @Test
    @DisplayName("should return 502 when Score returns a malformed body or one missing score")
    void malformedScoreBody_returns502() throws Exception {
        stubScore(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("not json{"));
        expectProblem(requestScore(customerId), 502);

        scoreWireMock.resetAll();
        stubScore(okJson("{\"cpf\":\"52998224725\",\"classification\":\"GOOD\"}"));
        expectProblem(requestScore(customerId), 502);
    }

    // AC-8 (ticket 05)
    // Given WireMock /scores/52998224725 -> 400 (and, separately, 422)
    // When GET /customers/C/score
    // Then each response is 502 problem+json ("status":502)
    //  And WireMock received exactly 1 request per call (4xx is never retried)
    @Test
    @DisplayName("should return 502 without retry when Score returns a 4xx other than 404")
    void other4xx_returns502WithoutRetry() throws Exception {
        for (int scoreStatus : List.of(400, 422)) {
            scoreWireMock.resetAll();
            stubScore(aResponse().withStatus(scoreStatus));

            expectProblem(requestScore(customerId), 502);

            scoreWireMock.verify(1, getRequestedFor(urlEqualTo(SCORE_URL)));
        }
    }

    // AC-9 (ticket 05) / NFR-6
    // Given WireMock /scores/52998224725 -> 500 always, and the circuit has been opened by 10 failing calls
    // When GET /customers/C/score is called again
    // Then the response is 503 problem+json
    //  And WireMock's request count does not increase
    //  (the < 100 ms latency target is asserted in ScoreResilienceNfrTest)
    @Test
    @DisplayName("should return 503 without calling Score when the circuit is open")
    void circuitOpen_returns503WithoutCallingScore() throws Exception {
        stubScore(aResponse().withStatus(500));
        for (int call = 0; call < CIRCUIT_WINDOW_CALLS; call++) {
            expectProblem(requestScore(customerId), 503);
        }
        assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int requestsBefore = scoreWireMock.getAllServeEvents().size();

        expectProblem(requestScore(customerId), 503);

        assertThat(scoreWireMock.getAllServeEvents()).hasSize(requestsBefore);
    }

    // AC-10 (ticket 05)
    // Given the files under docker/wiremock/mappings/
    // When they are parsed
    // Then there are stubs for GET /scores/{cpf} covering: 200 OK, 404, 500 and a delayed response (fixedDelayMilliseconds > 2000)
    //  And each stub's CPF is a valid 11-digit CPF
    //  And each of those CPFs appears in README.md
    // (Specific CPFs are chosen during the ticket 05 implementation — deferred by the user.)
    @Test
    @DisplayName("should provide OK, 404, 500 and slow WireMock stubs keyed by documented CPFs")
    void composeWireMockStubs_existForAllScenarios() throws Exception {
        Map<StubKind, String> cpfByKind = composeScoreStubs();

        assertThat(cpfByKind).containsOnlyKeys(StubKind.values());
        String readme = Files.readString(README);
        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = validatorFactory.getValidator();
            cpfByKind.forEach((kind, cpf) -> {
                assertThat(validator.validate(new CpfHolder(cpf))).as("CPF of the %s stub", kind).isEmpty();
                assertThat(readme).as("README lists the CPF of the %s stub", kind).contains(cpf);
            });
        }
    }

    private enum StubKind { OK, NOT_FOUND, SERVER_ERROR, SLOW }

    private record CpfHolder(@Cpf String cpf) {
    }

    private static Map<StubKind, String> composeScoreStubs() throws IOException {
        Map<StubKind, String> cpfByKind = new EnumMap<>(StubKind.class);
        try (Stream<Path> files = Files.list(WIREMOCK_MAPPINGS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                JsonNode mapping = JSON.readTree(file.toFile());
                Matcher url = SCORE_STUB_URL.matcher(mapping.path("request").path("url").asString());
                if ("GET".equals(mapping.path("request").path("method").asString()) && url.matches()) {
                    cpfByKind.put(kindOf(mapping.path("response")), url.group(1));
                }
            }
        }
        return cpfByKind;
    }

    private static StubKind kindOf(JsonNode response) {
        if (response.path("fixedDelayMilliseconds").asInt() > SLOW_STUB_MIN_DELAY_MS) {
            return StubKind.SLOW;
        }
        return switch (response.path("status").asInt()) {
            case 200 -> StubKind.OK;
            case 404 -> StubKind.NOT_FOUND;
            case 500 -> StubKind.SERVER_ERROR;
            default -> throw new AssertionError("Unexpected Score stub: " + response);
        };
    }

    private CircuitBreaker circuitBreaker() {
        return circuitBreakerRegistry.circuitBreaker(ScoreClient.RESILIENCE_INSTANCE);
    }

    private void stubScore(ResponseDefinitionBuilder response) {
        scoreWireMock.stubFor(WireMock.get(SCORE_URL).willReturn(response));
    }

    private ResultActions requestScore(long id) throws Exception {
        return mockMvc.perform(get(scorePath(id)).with(user()));
    }

    private static String scorePath(long id) {
        return "/customers/" + id + "/score";
    }

    private static RequestPostProcessor user() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static JsonNode expectProblem(ResultActions result, int expectedStatus) throws Exception {
        String body = result
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
