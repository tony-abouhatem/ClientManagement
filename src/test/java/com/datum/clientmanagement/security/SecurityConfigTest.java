package com.datum.clientmanagement.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Web slice without actuator or controllers: a 404 means the request passed the security filter chain.
 */
@WebMvcTest
@Import(SecurityConfig.class)
class SecurityConfigTest {

    private static final String UNMAPPED_API_PATH = "/customers";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpoint_isReachableWithoutToken() throws Exception {
        int status = mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void healthSubPaths_areReachableWithoutToken() throws Exception {
        int status = mockMvc.perform(get("/actuator/health/liveness")).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void otherEndpoints_withoutToken_returnUnauthorized() throws Exception {
        int status = mockMvc.perform(get(UNMAPPED_API_PATH)).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void otherEndpoints_withJwt_passAuthentication() throws Exception {
        int status = mockMvc.perform(get(UNMAPPED_API_PATH).with(jwt())).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void otherEndpoints_doNotCreateSession() throws Exception {
        var session = mockMvc.perform(get(UNMAPPED_API_PATH).with(jwt())).andReturn().getRequest().getSession(false);

        assertThat(session).isNull();
    }
}
