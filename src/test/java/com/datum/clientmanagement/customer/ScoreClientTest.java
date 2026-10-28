package com.datum.clientmanagement.customer;

import com.datum.clientmanagement.config.ScoreProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The HTTP adapter against an in-JVM WireMock (the network boundary). Resilience settings mirror the "score"
 * instances of application.yaml, with shorter waits and window so the tests stay fast.
 */
class ScoreClientTest {

    private static final String CPF = "52998224725";
    private static final String SCORE_URL = "/scores/" + CPF;
    private static final String OK_BODY = "{\"cpf\":\"52998224725\",\"score\":750,\"classification\":\"GOOD\"}";
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
    private static final int SLOWER_THAN_READ_TIMEOUT_MS = 1000;
    private static final int WINDOW_SIZE = 4;

    private static final WireMockServer SCORE = new WireMockServer(options().dynamicPort());

    private CircuitBreaker circuitBreaker;
    private ScoreClient client;

    @BeforeAll
    static void startScore() {
        SCORE.start();
    }

    @AfterAll
    static void stopScore() {
        SCORE.stop();
    }

    @BeforeEach
    void setUp() {
        SCORE.resetAll();
        client = clientFor(URI.create(SCORE.baseUrl()));
    }

    @Test
    void fetch_ok_returnsTheBodyAndCallsTheCpfPath() {
        SCORE.stubFor(get(SCORE_URL).willReturn(okJson(OK_BODY)));

        ExternalScore score = client.fetch(CPF);

        assertThat(score).isEqualTo(new ExternalScore(CPF, 750, "GOOD"));
        SCORE.verify(1, getRequestedFor(urlEqualTo(SCORE_URL)));
    }

    @Test
    void fetch_ignoresUnknownFieldsAndKeepsAnUnknownClassification() {
        SCORE.stubFor(get(SCORE_URL).willReturn(okJson(
                "{\"cpf\":\"52998224725\",\"score\":300,\"classification\":\"SOMETHING_NEW\",\"extra\":true}")));

        assertThat(client.fetch(CPF).classification()).isEqualTo("SOMETHING_NEW");
    }

    @Test
    void fetch_404_throwsScoreNotFoundWithDistinctTitleAndNoRetry() {
        SCORE.stubFor(get(SCORE_URL).willReturn(aResponse().withStatus(404)));

        ResponseStatusException exception = failure(() -> client.fetch(CPF), HttpStatus.NOT_FOUND);

        assertThat(exception.getBody().getTitle()).isEqualTo("Score not found");
        SCORE.verify(1, getRequestedFor(urlEqualTo(SCORE_URL)));
    }

    @Test
    void fetch_5xxThen200_retriesOnceAndSucceeds() {
        SCORE.stubFor(get(SCORE_URL).inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500)).willSetStateTo("recovered"));
        SCORE.stubFor(get(SCORE_URL).inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(okJson(OK_BODY)));

        assertThat(client.fetch(CPF).score()).isEqualTo(750);
        SCORE.verify(2, getRequestedFor(urlEqualTo(SCORE_URL)));
    }

    @Test
    void fetch_5xxOnBothAttempts_throwsUnavailable() {
        SCORE.stubFor(get(SCORE_URL).willReturn(aResponse().withStatus(503)));

        failure(() -> client.fetch(CPF), HttpStatus.SERVICE_UNAVAILABLE);

        SCORE.verify(2, getRequestedFor(urlEqualTo(SCORE_URL)));
    }

    @Test
    void fetch_other4xx_throwsInvalidResponseWithoutRetry() {
        SCORE.stubFor(get(SCORE_URL).willReturn(aResponse().withStatus(422)));

        failure(() -> client.fetch(CPF), HttpStatus.BAD_GATEWAY);

        SCORE.verify(1, getRequestedFor(urlEqualTo(SCORE_URL)));
    }

    @Test
    void fetch_malformedBody_throwsInvalidResponseWithoutRetry() {
        SCORE.stubFor(get(SCORE_URL).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("not json{")));

        failure(() -> client.fetch(CPF), HttpStatus.BAD_GATEWAY);
        SCORE.verify(1, getRequestedFor(urlEqualTo(SCORE_URL)));
    }

    @Test
    void fetch_bodyWithoutScore_throwsInvalidResponse() {
        SCORE.stubFor(get(SCORE_URL).willReturn(okJson("{\"cpf\":\"52998224725\",\"classification\":\"GOOD\"}")));

        failure(() -> client.fetch(CPF), HttpStatus.BAD_GATEWAY);
    }

    @Test
    void fetch_emptyBody_throwsInvalidResponse() {
        SCORE.stubFor(get(SCORE_URL).willReturn(aResponse().withStatus(200)));

        failure(() -> client.fetch(CPF), HttpStatus.BAD_GATEWAY);
    }

    @Test
    void fetch_slowerThanReadTimeout_throwsTimeoutAfterTwoAttempts() {
        SCORE.stubFor(get(SCORE_URL).willReturn(okJson(OK_BODY).withFixedDelay(SLOWER_THAN_READ_TIMEOUT_MS)));

        failure(() -> client.fetch(CPF), HttpStatus.GATEWAY_TIMEOUT);

        SCORE.verify(2, getRequestedFor(urlEqualTo(SCORE_URL)));
    }

    @Test
    void fetch_connectionRefused_throwsUnavailable() throws IOException {
        ScoreClient unreachable = clientFor(URI.create("http://localhost:" + closedPort()));

        failure(() -> unreachable.fetch(CPF), HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void fetch_circuitOpen_throwsUnavailableWithoutCallingScore() {
        SCORE.stubFor(get(SCORE_URL).willReturn(aResponse().withStatus(500)));
        for (int call = 0; call < WINDOW_SIZE; call++) {
            failure(() -> client.fetch(CPF), HttpStatus.SERVICE_UNAVAILABLE);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int requestsBefore = SCORE.getAllServeEvents().size();

        failure(() -> client.fetch(CPF), HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(SCORE.getAllServeEvents()).hasSize(requestsBefore);
    }

    @Test
    void fetch_repeatedNotFound_keepsTheCircuitClosed() {
        SCORE.stubFor(get(SCORE_URL).willReturn(aResponse().withStatus(404)));

        for (int call = 0; call < WINDOW_SIZE * 2; call++) {
            failure(() -> client.fetch(CPF), HttpStatus.NOT_FOUND);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private ScoreClient clientFor(URI baseUrl) {
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(WINDOW_SIZE)
                .minimumNumberOfCalls(WINDOW_SIZE)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        circuitBreaker = circuitBreakerRegistry.circuitBreaker(ScoreClient.RESILIENCE_INSTANCE);
        return new ScoreClient(new ScoreProperties(baseUrl, CONNECT_TIMEOUT, READ_TIMEOUT), retryRegistry,
                circuitBreakerRegistry);
    }

    private static ResponseStatusException failure(ThrowingCallable call, HttpStatus status) {
        ResponseStatusException exception = catchThrowableOfType(ResponseStatusException.class, call);
        assertThat(exception).as("expected a ResponseStatusException").isNotNull();
        assertThat(exception.getStatusCode()).isEqualTo(status);
        return exception;
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
