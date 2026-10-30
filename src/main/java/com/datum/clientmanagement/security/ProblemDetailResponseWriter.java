package com.datum.clientmanagement.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;

import java.io.IOException;
import java.net.URI;

/**
 * Writes an RFC 7807 body for errors raised inside the security filter chain, which runs before
 * Spring MVC and therefore never reaches the {@code @RestControllerAdvice}.
 */
final class ProblemDetailResponseWriter {

	// The no-arg constructor registers Spring's ProblemDetail mix-in, so the JSON has the RFC 7807 shape.
	private final JacksonJsonHttpMessageConverter converter = new JacksonJsonHttpMessageConverter();

	/** Writes a problem body using the status already set on the response. */
	void write(HttpServletRequest request, HttpServletResponse response, String detail) throws IOException {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(response.getStatus()), detail);
		problem.setInstance(URI.create(request.getRequestURI()));
		converter.write(problem, MediaType.APPLICATION_PROBLEM_JSON, new ServletServerHttpResponse(response));
	}
}
