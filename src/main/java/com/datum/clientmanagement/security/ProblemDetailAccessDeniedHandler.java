package com.datum.clientmanagement.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * Authenticated but missing the required role: keeps the standard bearer 403 challenge and adds a ProblemDetail body.
 */
final class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    static final String DETAIL = "You do not have permission to perform this operation.";

    private final BearerTokenAccessDeniedHandler bearerAccessDenied = new BearerTokenAccessDeniedHandler();
    private final ProblemDetailResponseWriter problemWriter;

    ProblemDetailAccessDeniedHandler(ProblemDetailResponseWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        bearerAccessDenied.handle(request, response, accessDeniedException);
        problemWriter.write(request, response, DETAIL);
    }
}
