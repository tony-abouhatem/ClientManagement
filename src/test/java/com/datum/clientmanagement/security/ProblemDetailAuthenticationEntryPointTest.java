package com.datum.clientmanagement.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

class ProblemDetailAuthenticationEntryPointTest {

	private static final String REQUEST_PATH = "/customers/1";
	private static final String TOKEN_ERROR_DETAIL = "Jwt expired at 2026-01-01T00:00:00Z";

	private final ProblemDetailAuthenticationEntryPoint entryPoint =
			new ProblemDetailAuthenticationEntryPoint(new ProblemDetailResponseWriter());

	@Test
	void commence_missingToken_writes401ProblemDetailWithBearerChallenge() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();

		entryPoint.commence(request(), response, new InsufficientAuthenticationException("Full authentication required"));

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
		assertThat(MediaType.parseMediaType(requireNonNull(response.getContentType()))).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
		JsonNode body = JsonMapper.builder().build().readTree(response.getContentAsString());
		assertThat(body.path("title").asString()).isEqualTo("Unauthorized");
		assertThat(body.path("status").asInt()).isEqualTo(401);
		assertThat(body.path("detail").asString()).isEqualTo(ProblemDetailAuthenticationEntryPoint.DETAIL);
		assertThat(body.path("instance").asString()).isEqualTo(REQUEST_PATH);
		assertThat(body.has("properties")).isFalse();
	}

	@Test
	void commence_invalidToken_signalsInvalidTokenInChallengeButNotInBody() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();

		entryPoint.commence(request(), response, new InvalidBearerTokenException(TOKEN_ERROR_DETAIL));

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer").contains("invalid_token");
		assertThat(response.getContentAsString())
				.contains(ProblemDetailAuthenticationEntryPoint.DETAIL)
				.doesNotContain(TOKEN_ERROR_DETAIL);
	}

	private static MockHttpServletRequest request() {
		return new MockHttpServletRequest("GET", REQUEST_PATH);
	}
}
