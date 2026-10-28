/*
 * NFR-3 (docs/PRODUCT.md); tickets 03 and 05 (GitHub issues #4, #6).
 *
 * Interfaces under test: application log output (capture with an in-memory appender / OutputCaptureExtension).
 * Log level for com.datum.clientmanagement set to DEBUG during these tests so every log statement is exercised.
 *
 * Mask format (confirmed by the user): only the last 2 digits are visible, e.g. "*********25" for 52998224725.
 */
package com.datum.clientmanagement.nonfunctional;

import com.datum.clientmanagement.ScoreWireMockConfiguration;
import com.datum.clientmanagement.TestcontainersConfiguration;
import com.datum.clientmanagement.customer.CustomerRepository;
import com.datum.clientmanagement.customer.ScoreClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "logging.level.com.datum.clientmanagement=DEBUG")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ScoreWireMockConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"}) // IDE has no database; the SQL runs against Testcontainers Postgres
class PrivacyLoggingNfrTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CONFIRMED_MASK = "*********25";
    // A run of asterisks plus the digits after it: whatever the code prints as a masked CPF.
    private static final Pattern MASKED_CPF = Pattern.compile("\\*+\\d*");
    private static final Pattern MENTIONS_CPF = Pattern.compile("\\bcpf\\b");
    private static final int READ_TIMEOUT_EXCEEDING_DELAY_MS = 3000;
    private static final String INSERT_CUSTOMER = "INSERT INTO customers"
            + " (name, name_normalized, cpf, email, status, created_at, updated_at)"
            + " VALUES ('Ana Souza', 'ana souza', ?, 'ana@example.com', 'ACTIVE', now(), now()) RETURNING id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WireMockServer scoreWireMock;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    // Real repository; only existsByCpf is stubbed, to let a duplicate reach the database.
    @MockitoSpyBean
    private CustomerRepository customerRepository;

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("DELETE FROM customers");
        scoreWireMock.resetAll();
        circuitBreakerRegistry.circuitBreaker(ScoreClient.RESILIENCE_INSTANCE).reset();
    }

    // NFR-3:privacy
    // Given log capture at DEBUG
    // When an ADMIN POSTs /customers with cpf "529.982.247-25" (201), then GETs, PUTs and DELETEs that customer,
    //  And POSTs the same cpf again (409) and POSTs cpf "52998224726" (400)
    // Then the captured log output contains neither "52998224725" nor "529.982.247-25" nor "52998224726"
    @Test
    @DisplayName("should never log the full CPF when customers are created, read, updated, deleted or rejected")
    void crudFlows_doNotLogFullCpf(CapturedOutput output) throws Exception {
        String maskedCpfBody = "{\"name\":\"Ana Souza\",\"cpf\":\"529.982.247-25\",\"email\":\"ana@example.com\"}";
        String createdBody = perform(jsonRequest(post("/customers"), maskedCpfBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String path = "/customers/" + JSON.readTree(createdBody).path("id").asLong();

        perform(get(path)).andExpect(status().isOk());
        perform(jsonRequest(put(path), "{\"name\":\"Ana Lima\",\"email\":\"ana@example.com\",\"status\":\"ACTIVE\","
                + "\"cpf\":\"52998224725\",\"version\":0}"))
                .andExpect(status().isOk());
        perform(jsonRequest(post("/customers"), maskedCpfBody)).andExpect(status().isConflict());
        perform(delete(path)).andExpect(status().isNoContent());
        perform(jsonRequest(post("/customers"),
                "{\"name\":\"Ana Souza\",\"cpf\":\"52998224726\",\"email\":\"ana@example.com\"}"))
                .andExpect(status().isBadRequest());

        assertThat(output.getAll()).doesNotContain("52998224725", "529.982.247-25", "52998224726");
    }

    // NFR-3:privacy — duplicate CPF stopped only by the database (two creations racing past the up-front check)
    // Given the up-front CPF check misses the duplicate, so PostgreSQL's unique constraint rejects the INSERT
    // When an ADMIN POSTs /customers with cpf "52998224725" twice (201, then 409)
    // Then the captured log output does not contain "52998224725" (PostgreSQL puts it in the error detail,
    //      "Key (cpf)=(52998224725) already exists.")
    @Test
    @DisplayName("should not log the full CPF when the database rejects a duplicate CPF")
    void duplicateCpfRejectedByDatabase_doesNotLogFullCpf(CapturedOutput output) throws Exception {
        doReturn(false).when(customerRepository).existsByCpf(anyString());
        String body = "{\"name\":\"Ana Souza\",\"cpf\":\"52998224725\",\"email\":\"ana@example.com\"}";

        perform(jsonRequest(post("/customers"), body)).andExpect(status().isCreated());
        perform(jsonRequest(post("/customers"), body)).andExpect(status().isConflict());

        assertThat(output.getAll()).doesNotContain("52998224725");
    }

    // NFR-3:privacy — ticket 05 edge case
    // Given log capture at DEBUG and WireMock /scores/11144477735 returning 200, then 500, then a 3 s delay
    // When GET /customers/{id}/score is called for the customer with cpf 11144477735 in each scenario
    // Then the captured log output never contains "11144477735" (including HTTP client / retry / circuit-breaker logs)
    @Test
    @DisplayName("should never log the full CPF when calling the Score service, including on failures")
    void scoreCalls_doNotLogFullCpf(CapturedOutput output) throws Exception {
        String cpf = "11144477735";
        Long id = jdbcTemplate.queryForObject(INSERT_CUSTOMER, Long.class, cpf);
        String okBody = "{\"cpf\":\"11144477735\",\"score\":750,\"classification\":\"GOOD\"}";
        List<ResponseDefinitionBuilder> responses = List.of(
                okJson(okBody),
                aResponse().withStatus(500),
                okJson(okBody).withFixedDelay(READ_TIMEOUT_EXCEEDING_DELAY_MS));
        List<Integer> expectedStatuses = List.of(200, 503, 504);

        for (int scenario = 0; scenario < responses.size(); scenario++) {
            scoreWireMock.resetAll();
            stubScore(cpf, responses.get(scenario));

            perform(get(scorePath(id))).andExpect(status().is(expectedStatuses.get(scenario)));
        }

        assertThat(output.getAll()).doesNotContain(cpf);
    }

    // NFR-3:privacy — confirmed mask: 9 asterisks + last 2 digits
    // Given log capture at DEBUG
    // When an ADMIN creates a customer with cpf "52998224725" (201) and then POSTs the same cpf again (409),
    //  And GET /customers/{id}/score is called for it with WireMock /scores/52998224725 returning 500
    // Then every log line that refers to the CPF shows exactly "*********25"
    //  And at least one such line exists (the CPF is logged in masked form, e.g. on the duplicate-CPF rejection)
    //  And no log line contains "52998224725", "529.982.247-25", "529982247" or any CPF fragment longer than
    //      the last 2 digits (e.g. "4725")
    //  ("4725" is checked everywhere and every longer suffix contains it; 3-digit fragments are covered by the mask
    //  check only, because they also occur by chance in timestamps and ports.)
    @Test
    @DisplayName("should log the CPF as *********25 when a CPF of 52998224725 is logged")
    void loggedCpf_usesConfirmedMaskFormat(CapturedOutput output) throws Exception {
        String body = "{\"name\":\"Ana Souza\",\"cpf\":\"52998224725\",\"email\":\"ana@example.com\"}";
        String createdBody = perform(jsonRequest(post("/customers"), body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = JSON.readTree(createdBody).path("id").asLong();
        perform(jsonRequest(post("/customers"), body)).andExpect(status().isConflict());
        stubScore("52998224725", aResponse().withStatus(500));
        perform(get(scorePath(id))).andExpect(status().isServiceUnavailable());

        String logs = output.getAll();
        List<String> cpfLines = logs.lines()
                .filter(line -> MENTIONS_CPF.matcher(line.toLowerCase(Locale.ROOT)).find())
                .toList();
        assertThat(cpfLines).isNotEmpty().allSatisfy(line -> assertThat(line).contains(CONFIRMED_MASK));
        Matcher masked = MASKED_CPF.matcher(logs);
        while (masked.find()) {
            assertThat(masked.group()).isEqualTo(CONFIRMED_MASK);
        }
        assertThat(logs).doesNotContain("52998224725", "529.982.247-25", "529982247", "4725");
    }

    private void stubScore(String cpf, ResponseDefinitionBuilder response) {
        scoreWireMock.stubFor(WireMock.get("/scores/" + cpf).willReturn(response));
    }

    private static String scorePath(long id) {
        return "/customers/" + id + "/score";
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private static MockHttpServletRequestBuilder jsonRequest(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
