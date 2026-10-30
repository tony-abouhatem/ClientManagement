package com.datum.clientmanagement.service;

import com.datum.clientmanagement.config.ScoreProperties;
import com.datum.clientmanagement.dto.ExternalScore;
import com.datum.clientmanagement.util.CustomerFormats;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.function.Supplier;

/**
 * Calls the external {@code GET /scores/{cpf}} (ADR-4) with the connect/read timeouts of {@link ScoreProperties},
 * Resilience4j retry "score" inside circuit breaker "score" (NFR-4, NFR-6). Which failures are retried and recorded
 * is configured in {@code application.yaml} by Spring's own client exceptions (5xx and I/O errors). Once the
 * resilient call has given up, the failure becomes a {@link ResponseStatusException} with the NFR-5 status.
 * <p>
 * Underlying exceptions are neither logged nor kept as causes: their messages contain the request URL, and with it
 * the full CPF (NFR-3). Logs show the masked CPF and the failure kind instead.
 */
@Component
public class ScoreClient {

	public static final String RESILIENCE_INSTANCE = "score";
	private static final String SCORE_NOT_FOUND_TITLE = "Score not found";
	private static final String SCORE_NOT_FOUND_MESSAGE = "The Score service has no score for this customer.";
	private static final String UNAVAILABLE_MESSAGE = "The Score service is unavailable. Try again later.";
	private static final String TIMEOUT_MESSAGE = "The Score service did not respond in time.";
	private static final String INVALID_RESPONSE_MESSAGE = "The Score service returned an invalid response.";

	private static final String SCORE_PATH = "/scores/{cpf}";
	private final Logger log = LoggerFactory.getLogger(ScoreClient.class);

	private final RestClient restClient;
	private final Retry retry;
	private final CircuitBreaker circuitBreaker;
	private final CustomerFormats formats;

	public ScoreClient(ScoreProperties properties, RetryRegistry retryRegistry,
			CircuitBreakerRegistry circuitBreakerRegistry, CustomerFormats formats) {
		this.restClient = RestClient.builder()
				.baseUrl(properties.baseUrl().toString())
				.requestFactory(requestFactory(properties))
				.build();
		this.retry = retryRegistry.retry(RESILIENCE_INSTANCE);
		this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE);
		this.formats = formats;
	}

	/**
	 * Fetches the score for a normalized 11-digit CPF. Failures are thrown as {@link ResponseStatusException}s:
	 * 404 (title "Score not found"), 502 (invalid answer), 503 (unavailable or circuit open), 504 (read timeout).
	 */
	public ExternalScore fetch(String cpf) {
		Supplier<ExternalScore> resilientCall = CircuitBreaker.decorateSupplier(circuitBreaker,
				Retry.decorateSupplier(retry,
						() -> restClient.get().uri(SCORE_PATH, cpf).retrieve().body(ExternalScore.class)));
		try {
			return requireScore(resilientCall.get(), cpf);
		} catch (CallNotPermittedException exception) {
			log.warn("Score call for CPF {} not attempted: the circuit breaker is open", mask(cpf));
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
		} catch (HttpClientErrorException.NotFound exception) {
			log.info("Score has no entry for CPF {}", mask(cpf));
			throw scoreNotFound();
		} catch (HttpServerErrorException exception) {
			log.warn("Score call for CPF {} failed with status {}", mask(cpf), exception.getStatusCode().value());
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
		} catch (HttpClientErrorException exception) {
			log.warn("Score call for CPF {} was rejected with status {}", mask(cpf),
					exception.getStatusCode().value());
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, INVALID_RESPONSE_MESSAGE);
		} catch (ResourceAccessException exception) {
			throw ioFailure(exception, cpf);
		} catch (RestClientException exception) {
			log.warn("Score response for CPF {} could not be read ({})", mask(cpf),
					exception.getClass().getSimpleName());
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, INVALID_RESPONSE_MESSAGE);
		}
	}

	private ExternalScore requireScore(ExternalScore score, String cpf) {
		if (score == null || score.score() == null) {
			log.warn("Score for CPF {} has no score value", mask(cpf));
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, INVALID_RESPONSE_MESSAGE);
		}
		return score;
	}

	/** Its own title, so clients can tell it apart from "customer not found" (NFR-5). */
	private ResponseStatusException scoreNotFound() {
		ResponseStatusException exception = new ResponseStatusException(HttpStatus.NOT_FOUND, SCORE_NOT_FOUND_MESSAGE);
		exception.getBody().setTitle(SCORE_NOT_FOUND_TITLE);
		return exception;
	}

	/** Only a read timeout means "slow" (504); refused connections and connect timeouts mean "unavailable" (503). */
	private ResponseStatusException ioFailure(ResourceAccessException exception, String cpf) {
		Throwable cause = exception.getCause();
		if (cause instanceof HttpTimeoutException && !(cause instanceof HttpConnectTimeoutException)) {
			log.warn("Score call for CPF {} timed out waiting for the response", mask(cpf));
			return new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, TIMEOUT_MESSAGE);
		}
		log.warn("Score call for CPF {} could not connect ({})", mask(cpf),
				cause == null ? exception.getClass().getSimpleName() : cause.getClass().getSimpleName());
		return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
	}

	private ClientHttpRequestFactory requestFactory(ScoreProperties properties) {
		HttpClient httpClient = HttpClient.newBuilder()
				// Plain HTTP/1.1: the default HTTP/2 mode would send an h2c upgrade request to the Score service.
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(properties.connectTimeout())
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());
		return requestFactory;
	}

	private String mask(String cpf) {
		return formats.maskCpf(cpf);
	}
}
