/*
 * NFR-1, NFR-2 (docs/PRODUCT.md); tickets 02, 03, 04 (GitHub issues #3, #4, #5).
 *
 * Interfaces under test (do not exist yet): SecurityFilterChain in com.datum.clientmanagement.security,
 * ProblemDetail entry point / access-denied handler, all HTTP endpoints.
 */
package com.datum.clientmanagement.nonfunctional;

import com.datum.clientmanagement.TestcontainersConfiguration;
import com.datum.clientmanagement.customer.CustomerService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class SecurityNfrTest {

    private static final long FORCED_FAILURE_ID = 424242L;
    private static final List<String> LEAK_MARKERS =
            List.of("Exception", "org.springframework", "org.hibernate", "SQL", "at com.datum", "trace");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private CustomerService customerService;

    // NFR-2:security (allow-list)
    // Given no Authorization header
    // When GET /actuator/env, /actuator/beans, /actuator/configprops, /actuator/metrics and /actuator (root) are called
    // Then each response is 401 or 404 — never 200 (only /actuator/health/** is public)
    @Test
    @DisplayName("should not expose actuator endpoints other than health when called without a token")
    void nonHealthActuatorEndpoints_areNotPublic() throws Exception {
        List<String> paths = List.of("/actuator/env", "/actuator/beans", "/actuator/configprops", "/actuator/metrics",
                "/actuator");

        for (String path : paths) {
            assertThat(statusOf(path)).as("GET %s", path).isIn(401, 404);
        }
    }

    // NFR-2:security
    // Given no Authorization header
    // When GET /actuator/health/liveness and /actuator/health/readiness are called
    // Then each response is not 401/403 (health subpaths are public)
    @Test
    @DisplayName("should allow health subpaths without a token")
    void healthSubpaths_arePublic() throws Exception {
        for (String path : List.of("/actuator/health/liveness", "/actuator/health/readiness")) {
            assertThat(statusOf(path)).as("GET %s", path).isNotIn(401, 403);
        }
    }

    // NFR-1:security
    // Given no Authorization header
    // When GET /customers is called
    // Then the 401 response has header WWW-Authenticate starting with "Bearer"
    //  And the body is problem+json with no "trace" and no "exception" fields
    @Test
    @DisplayName("should return a Bearer challenge and a clean problem body when the token is missing")
    void unauthorized_hasBearerChallengeAndNoInternals() throws Exception {
        mockMvc.perform(get("/customers"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(result -> assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                        .startsWith("Bearer"));
    }

    // NFR-1:security (stateless)
    // Given an ADMIN request to GET /customers that succeeds
    // When the response is inspected
    // Then there is no Set-Cookie header (no JSESSIONID; stateless session)
    @Test
    @DisplayName("should not create an HTTP session when an authenticated request is served")
    void noSessionCookie_isIssued() throws Exception {
        MvcResult result = mockMvc.perform(get("/customers")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andReturn();
        MockHttpServletResponse response = result.getResponse();

        // Until ticket 04 adds the list endpoint the request ends in 405; it must still pass authorization.
        assertThat(response.getStatus()).isNotIn(401, 403);
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    // NFR:security (injection) — ticket 04
    // Given customers "Ana Souza" and "Bruno Lima"
    // When GET /customers/search?name=' OR '1'='1 and GET /customers?name=%27%3B%20DROP%20TABLE%20customers%3B--
    // Then each response is 200 with empty content
    //  And the customers table still has 2 rows
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should treat SQL injection payloads in name as literal text")
    void sqlInjectionInName_isInert() { }

    // NFR:security (information leakage) — ticket 03 / NFR-13
    // Given an ADMIN
    // When requests that produce 400 (malformed JSON), 404 (unknown id), 409 (duplicate CPF) and 500 (forced failure) are made
    // Then no response body contains "Exception", "org.springframework", "org.hibernate", "SQL", "at com.datum" or "trace"
    @Test
    @DisplayName("should not leak stack traces, class names or SQL in any error response")
    void errorResponses_doNotLeakInternals() throws Exception {
        jdbcTemplate.update("DELETE FROM customers");
        String customerBody = "{\"name\":\"Ana Souza\",\"cpf\":\"52998224725\",\"email\":\"ana@example.com\"}";
        mockMvc.perform(adminJson(post("/customers"), customerBody)).andExpect(status().isCreated());
        doThrow(new IllegalStateException("org.hibernate.exception.SQLGrammarException: SQL [select * from customers]"))
                .when(customerService).findById(FORCED_FAILURE_ID);

        Map<String, MockHttpServletRequestBuilder> requestsByExpectedStatus = Map.of(
                "400", adminJson(post("/customers"), "{\"name\": \"Ana\", "),
                "404", get("/customers/999999").with(admin()),
                "409", adminJson(post("/customers"), customerBody),
                "500", get("/customers/" + FORCED_FAILURE_ID).with(admin()));

        for (Map.Entry<String, MockHttpServletRequestBuilder> entry : requestsByExpectedStatus.entrySet()) {
            MockHttpServletResponse response = mockMvc.perform(entry.getValue()).andReturn().getResponse();

            assertThat(response.getStatus()).as("status").isEqualTo(Integer.parseInt(entry.getKey()));
            assertThat(response.getContentAsString()).as("body of the %s response", entry.getKey())
                    .doesNotContain(LEAK_MARKERS);
        }
    }

    private int statusOf(String path) throws Exception {
        return mockMvc.perform(get(path)).andReturn().getResponse().getStatus();
    }

    private static MockHttpServletRequestBuilder adminJson(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body).with(admin());
    }

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
