/*
 * NFR-3 (docs/PRODUCT.md); tickets 03 and 05 (GitHub issues #4, #6).
 *
 * Interfaces under test: application log output (capture with an in-memory appender / OutputCaptureExtension).
 * Log level for com.datum.clientmanagement set to DEBUG during these tests so every log statement is exercised.
 *
 * Mask format (confirmed by the user): only the last 2 digits are visible, e.g. "*********25" for 52998224725.
 */
package com.datum.clientmanagement.nonfunctional;

import com.datum.clientmanagement.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "logging.level.com.datum.clientmanagement=DEBUG")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"}) // IDE has no database; the SQL runs against Testcontainers Postgres
class PrivacyLoggingNfrTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void deleteAllCustomers() {
        jdbcTemplate.update("DELETE FROM customers");
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

    // NFR-3:privacy — ticket 05 edge case
    // Given log capture at DEBUG and WireMock /scores/11144477735 returning 200, then 500, then a 3 s delay
    // When GET /customers/{id}/score is called for the customer with cpf 11144477735 in each scenario
    // Then the captured log output never contains "11144477735" (including HTTP client / retry / circuit-breaker logs)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should never log the full CPF when calling the Score service, including on failures")
    void scoreCalls_doNotLogFullCpf() { }

    // NFR-3:privacy — confirmed mask: 9 asterisks + last 2 digits
    // Given log capture at DEBUG
    // When an ADMIN creates a customer with cpf "52998224725" (201) and then POSTs the same cpf again (409),
    //  And GET /customers/{id}/score is called for it with WireMock /scores/52998224725 returning 500
    // Then every log line that refers to the CPF shows exactly "*********25"
    //  And at least one such line exists (the CPF is logged in masked form, e.g. on the duplicate-CPF rejection)
    //  And no log line contains "52998224725", "529.982.247-25", "529982247" or any CPF fragment longer than
    //      the last 2 digits (e.g. "4725")
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should log the CPF as *********25 when a CPF of 52998224725 is logged")
    void loggedCpf_usesConfirmedMaskFormat() { }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private static MockHttpServletRequestBuilder jsonRequest(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
