package com.datum.clientmanagement.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDetailAccessDeniedHandlerTest {

    private static final String REQUEST_PATH = "/customers";

    private final ProblemDetailAccessDeniedHandler handler =
            new ProblemDetailAccessDeniedHandler(new ProblemDetailResponseWriter());

    @Test
    void handle_accessDenied_writes403ProblemDetail() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(new MockHttpServletRequest("POST", REQUEST_PATH), response, new AccessDeniedException("Access Denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(MediaType.parseMediaType(response.getContentType())).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode body = JsonMapper.builder().build().readTree(response.getContentAsString());
        assertThat(body.path("title").asString()).isEqualTo("Forbidden");
        assertThat(body.path("status").asInt()).isEqualTo(403);
        assertThat(body.path("detail").asString()).isEqualTo(ProblemDetailAccessDeniedHandler.DETAIL);
        assertThat(body.path("instance").asString()).isEqualTo(REQUEST_PATH);
    }
}
