package com.datum.clientmanagement.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Missing or invalid bearer token: keeps the standard {@code WWW-Authenticate: Bearer} challenge and status,
 * and adds a ProblemDetail body. The detail is fixed so token-validation internals are not echoed in the body.
 */
final class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

	static final String DETAIL = "A valid bearer token is required to access this resource.";

	private final BearerTokenAuthenticationEntryPoint bearerChallenge = new BearerTokenAuthenticationEntryPoint();
	private final ProblemDetailResponseWriter problemWriter;

	ProblemDetailAuthenticationEntryPoint(ProblemDetailResponseWriter problemWriter) {
		this.problemWriter = problemWriter;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
						 AuthenticationException authException) throws IOException {
		bearerChallenge.commence(request, response, authException);
		problemWriter.write(request, response, DETAIL);
	}
}
