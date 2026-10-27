/*
 * Ticket: 03 (GitHub issue #4)
 *
 * Interfaces under test (do not exist yet):
 *   - POST /customers, PUT /customers/{id}, DELETE /customers/{id}, GET /customers/{id}
 *   - DTOs (package com.datum.clientmanagement.customer): CustomerRequests.Create(name, cpf, email, status?),
 *     CustomerRequests.Update(name, email, status, version, cpf?) — version REQUIRED (optimistic lock),
 *     CustomerResponse(id, name, cpf, email, status, createdAt, updatedAt, version)
 *   - com.datum.clientmanagement.common.error @RestControllerAdvice -> ProblemDetail
 *     {title,status,detail,instance[,errors[{field,message}]]}, Content-Type application/problem+json
 *
 * All requests use an ADMIN jwt() unless stated. Integration against Testcontainers Postgres.
 * Valid CPFs used: 52998224725, 11144477735, 39053344705, 93541134780.
 */
package com.datum.clientmanagement.functional;

import com.datum.clientmanagement.TestcontainersConfiguration;
import com.datum.clientmanagement.customer.CustomerService;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"}) // IDE has no database; the SQL runs against Testcontainers Postgres
class CustomerCrudFunctionalTest {

    private static final String CUSTOMERS = "/customers";
    private static final String ANA_CPF = "52998224725";
    private static final String ANA_BODY =
            "{\"name\":\"Ana Souza\",\"cpf\":\"52998224725\",\"email\":\"ana@example.com\"}";
    private static final long UNKNOWN_ID = 999999L;
    private static final long CONCURRENT_TIMEOUT_SECONDS = 30;
    private static final List<String> STACK_TRACE_MARKERS = List.of("Exception", "at com.", "at org.", "trace");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private CustomerService customerService;

    @BeforeEach
    void deleteAllCustomers() {
        jdbcTemplate.update("DELETE FROM customers");
    }

    // AC-1 (ticket 03)
    // Given an ADMIN and no customers
    // When POST /customers {"name":"Ana Souza","cpf":"52998224725","email":"ana@example.com"} (no status)
    // Then the response is 201
    //  And header Location == "/customers/{id}" where {id} == body.id (numeric)
    //  And body == {id:<id>, name:"Ana Souza", cpf:"52998224725", email:"ana@example.com", status:"ACTIVE",
    //               createdAt:<ISO-8601>, updatedAt:<ISO-8601>, version:0}
    @Test
    @DisplayName("should return 201 with Location and status ACTIVE when an ADMIN creates a valid customer without status")
    void createCustomer_returns201() throws Exception {
        MockHttpServletResponse response = createRequest(ANA_BODY)
                .andExpect(status().isCreated())
                .andReturn().getResponse();

        JsonNode body = JSON.readTree(response.getContentAsString());
        assertThat(body.path("id").isIntegralNumber()).isTrue();
        long id = body.path("id").asLong();
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isEqualTo(CUSTOMERS + "/" + id);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder(
                "id", "name", "cpf", "email", "status", "createdAt", "updatedAt", "version");
        assertThat(body.path("name").asString()).isEqualTo("Ana Souza");
        assertThat(body.path("cpf").asString()).isEqualTo(ANA_CPF);
        assertThat(body.path("email").asString()).isEqualTo("ana@example.com");
        assertThat(body.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(body.path("version").isIntegralNumber()).isTrue();
        assertThat(body.path("version").asLong()).isZero();
        assertThat(Instant.parse(body.path("createdAt").asString())).isNotNull();
        assertThat(Instant.parse(body.path("updatedAt").asString())).isNotNull();
    }

    // AC-1 (ticket 03)
    // Given an ADMIN
    // When POST /customers {"name":"Bruno Lima","cpf":"11144477735","email":"bruno@example.com","status":"INACTIVE"}
    // Then the response is 201 and body.status == "INACTIVE"
    @Test
    @DisplayName("should keep the given status when POST includes status INACTIVE")
    void createCustomerWithExplicitStatus_keepsStatus() throws Exception {
        createRequest("{\"name\":\"Bruno Lima\",\"cpf\":\"11144477735\",\"email\":\"bruno@example.com\",\"status\":\"INACTIVE\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    // AC-2 (ticket 03)
    // Given an ADMIN
    // When POST /customers {"name":"Ana Souza","cpf":"529.982.247-25","email":"ana@example.com"}
    // Then the response is 201 with body.cpf == "52998224725"
    //  And the customers row has cpf == "52998224725"
    //  And GET /customers/{id} returns cpf "52998224725"
    @Test
    @DisplayName("should store and return the CPF as 11 digits when a masked CPF is posted")
    void maskedCpf_isNormalized() throws Exception {
        JsonNode created = createCustomer("{\"name\":\"Ana Souza\",\"cpf\":\"529.982.247-25\",\"email\":\"ana@example.com\"}");
        long id = created.path("id").asLong();

        assertThat(created.path("cpf").asString()).isEqualTo(ANA_CPF);
        assertThat(jdbcTemplate.queryForObject("SELECT cpf FROM customers WHERE id = ?", String.class, id))
                .isEqualTo(ANA_CPF);
        mockMvc.perform(get(customerPath(id)).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cpf").value(ANA_CPF));
    }

    // AC-2 (ticket 03) edge case: surrounding spaces / mixed punctuation
    // Given an ADMIN
    // When POST /customers with cpf "  529.982247-25 "
    // Then the response is 201 with body.cpf == "52998224725"
    @Test
    @DisplayName("should normalize the CPF when it has surrounding spaces or mixed punctuation")
    void cpfWithSpacesAndMixedPunctuation_isNormalized() throws Exception {
        createRequest("{\"name\":\"Ana Souza\",\"cpf\":\"  529.982247-25 \",\"email\":\"ana@example.com\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cpf").value(ANA_CPF));
    }

    // AC-3 (ticket 03)
    // Given an ADMIN
    // When POST /customers {"name":"Ana Souza","cpf":"52998224726","email":"ana@example.com"} (wrong check digit)
    // Then the response is 400 problem+json, "status":400
    //  And errors[] contains an entry with field == "cpf" and a non-empty message
    //  And no customer row is written
    @Test
    @DisplayName("should return 400 with errors[].field=cpf when the CPF check digits are invalid")
    void invalidCheckDigits_returns400() throws Exception {
        JsonNode problem = expectProblem(
                createRequest("{\"name\":\"Ana Souza\",\"cpf\":\"52998224726\",\"email\":\"ana@example.com\"}"), 400);

        assertThat(errorFields(problem)).contains("cpf");
        assertThat(errorMessage(problem, "cpf")).isNotBlank();
        assertThat(customerCount()).isZero();
    }

    // AC-3 (ticket 03)
    // Given an ADMIN
    // When POST /customers with cpf "11111111111" (also "111.111.111-11" and "00000000000")
    // Then each response is 400 with errors[] containing field == "cpf"
    @Test
    @DisplayName("should return 400 with errors[].field=cpf when the CPF is a repeated-digit sequence")
    void repeatedDigitCpf_returns400() throws Exception {
        for (String cpf : List.of("11111111111", "111.111.111-11", "00000000000")) {
            JsonNode problem = expectProblem(createRequest(bodyWithCpf(cpf)), 400);

            assertThat(errorFields(problem)).as("cpf %s", cpf).contains("cpf");
        }
    }

    // AC-3 (ticket 03)
    // Given an ADMIN
    // When POST /customers with cpf "5299822472" (10 digits) and, separately, "529982247250" (12 digits)
    // Then each response is 400 with errors[] containing field == "cpf"
    @Test
    @DisplayName("should return 400 with errors[].field=cpf when the CPF does not have 11 digits")
    void wrongLengthCpf_returns400() throws Exception {
        for (String cpf : List.of("5299822472", "529982247250")) {
            JsonNode problem = expectProblem(createRequest(bodyWithCpf(cpf)), 400);

            assertThat(errorFields(problem)).as("cpf %s", cpf).contains("cpf");
        }
    }

    // AC-4 (ticket 03) / NFR-13
    // Given an ADMIN
    // When POST /customers is called with each single invalid field (others valid, cpf 52998224725):
    //   name "   "                     -> errors[].field "name"
    //   name of 151 chars ("a" x 151)  -> errors[].field "name"
    //   email "not-an-email"           -> errors[].field "email"
    //   email omitted                  -> errors[].field "email"
    //   status "FOO"                   -> errors[].field "status"
    // Then each response is 400 problem+json with exactly 1 errors[] entry for the named field
    @Test
    @DisplayName("should return 400 with one error for the invalid field when POST has an invalid name, email or status")
    void postInvalidSingleField_returns400() throws Exception {
        Map<String, String> bodyToInvalidField = Map.of(
                "{\"name\":\"   \",\"cpf\":\"52998224725\",\"email\":\"ana@example.com\"}", "name",
                "{\"name\":\"" + "a".repeat(151) + "\",\"cpf\":\"52998224725\",\"email\":\"ana@example.com\"}", "name",
                "{\"name\":\"Ana Souza\",\"cpf\":\"52998224725\",\"email\":\"not-an-email\"}", "email",
                "{\"name\":\"Ana Souza\",\"cpf\":\"52998224725\"}", "email",
                "{\"name\":\"Ana Souza\",\"cpf\":\"52998224725\",\"email\":\"ana@example.com\",\"status\":\"FOO\"}", "status");

        for (Map.Entry<String, String> entry : bodyToInvalidField.entrySet()) {
            JsonNode problem = expectProblem(createRequest(entry.getKey()), 400);

            assertThat(errorFields(problem)).as("body %s", entry.getKey()).containsExactly(entry.getValue());
        }
        assertThat(customerCount()).isZero();
    }

    // AC-4 (ticket 03) boundary
    // Given an ADMIN
    // When POST /customers with name of exactly 150 chars ("a" x 150), cpf 39053344705, email "max@example.com"
    // Then the response is 201
    @Test
    @DisplayName("should accept the customer when the name has exactly 150 characters")
    void nameAt150Chars_isAccepted() throws Exception {
        String name = "a".repeat(150);

        createRequest("{\"name\":\"" + name + "\",\"cpf\":\"39053344705\",\"email\":\"max@example.com\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name));
    }

    // AC-4 (ticket 03) / NFR-13
    // Given an ADMIN
    // When POST /customers {"name":"","cpf":"52998224725","email":"bad"} is called
    // Then the response is 400 with exactly 2 errors[] entries: field "name" and field "email"
    @Test
    @DisplayName("should return one errors[] entry per invalid field when several fields are invalid")
    void multipleInvalidFields_returnsOneErrorPerField() throws Exception {
        JsonNode problem = expectProblem(createRequest("{\"name\":\"\",\"cpf\":\"52998224725\",\"email\":\"bad\"}"), 400);

        assertThat(errorFields(problem)).containsExactlyInAnyOrder("name", "email");
    }

    // AC-4 (ticket 03)
    // Given an existing customer id X (cpf 52998224725) at version 0
    // When PUT /customers/X {"name":"","email":"bad","status":"FOO","version":0}
    // Then the response is 400 with errors[] entries for fields "name", "email", "status"
    //  And the stored customer is unchanged
    @Test
    @DisplayName("should return 400 with one error per invalid field when PUT has invalid name, email and status")
    void putInvalidFields_returns400() throws Exception {
        JsonNode created = createCustomer(ANA_BODY);
        long id = created.path("id").asLong();

        JsonNode problem = expectProblem(
                updateRequest(id, "{\"name\":\"\",\"email\":\"bad\",\"status\":\"FOO\",\"version\":0}"), 400);

        assertThat(errorFields(problem)).containsExactlyInAnyOrder("name", "email", "status");
        assertThat(getCustomer(id)).isEqualTo(created);
    }

    // AC-5 (ticket 03)
    // Given an existing customer with cpf "52998224725"
    // When POST /customers {"name":"Outra Pessoa","cpf":"52998224725","email":"outra@example.com"}
    //  and, separately, the same body with cpf "529.982.247-25"
    // Then each response is 409 problem+json, "status":409
    //  And only one customers row has cpf 52998224725
    @Test
    @DisplayName("should return 409 when POST uses an existing CPF, masked or raw")
    void duplicateCpf_returns409() throws Exception {
        createCustomer(ANA_BODY);

        for (String cpf : List.of("52998224725", "529.982.247-25")) {
            expectProblem(createRequest(
                    "{\"name\":\"Outra Pessoa\",\"cpf\":\"" + cpf + "\",\"email\":\"outra@example.com\"}"), 409);
        }
        assertThat(customerCountWithCpf(ANA_CPF)).isEqualTo(1);
    }

    // AC-5 (ticket 03)
    // Given no customer with cpf "93541134780"
    // When 2 threads concurrently POST /customers with cpf "93541134780" (different names/emails)
    // Then exactly one response is 201 and the other is 409 (never 500)
    //  And exactly one customers row has cpf 93541134780
    @Test
    @DisplayName("should return 409 for the loser when two concurrent POSTs use the same CPF")
    void concurrentDuplicateCpf_returns409ForLoser() throws Exception {
        List<Integer> statuses = runConcurrently(
                () -> statusOf(jsonRequest(post(CUSTOMERS),
                        "{\"name\":\"Racer One\",\"cpf\":\"93541134780\",\"email\":\"one@example.com\"}")),
                () -> statusOf(jsonRequest(post(CUSTOMERS),
                        "{\"name\":\"Racer Two\",\"cpf\":\"93541134780\",\"email\":\"two@example.com\"}")));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(customerCountWithCpf("93541134780")).isEqualTo(1);
    }

    // Edge case (ticket 03): email is not unique
    // Given an existing customer with email "shared@example.com"
    // When POST /customers {"name":"Carla Dias","cpf":"11144477735","email":"shared@example.com"}
    // Then the response is 201
    @Test
    @DisplayName("should allow creation when the email is already used by another customer")
    void duplicateEmail_isAllowed() throws Exception {
        createCustomer("{\"name\":\"Ana Souza\",\"cpf\":\"52998224725\",\"email\":\"shared@example.com\"}");

        createRequest("{\"name\":\"Carla Dias\",\"cpf\":\"11144477735\",\"email\":\"shared@example.com\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("shared@example.com"));
    }

    // Edge case (ticket 03): name is trimmed
    // Given an ADMIN
    // When POST /customers with name "  João da Silva  ", cpf 39053344705
    // Then body.name == "João da Silva"
    @Test
    @DisplayName("should trim the name when it has surrounding spaces")
    void nameIsTrimmed() throws Exception {
        createRequest("{\"name\":\"  João da Silva  \",\"cpf\":\"39053344705\",\"email\":\"joao@example.com\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("João da Silva"));
    }

    // AC-6 (ticket 03)
    // Given customer X {name:"Ana Souza", cpf:"52998224725", email:"ana@example.com", status:ACTIVE, version:0} with updatedAt T0
    // When PUT /customers/X {"name":"Ana S. Lima","email":"ana.lima@example.com","status":"INACTIVE","version":0} (no cpf)
    // Then the response is 200 with name "Ana S. Lima", email "ana.lima@example.com", status "INACTIVE",
    //      cpf "52998224725", version 1, createdAt unchanged, updatedAt > T0
    //  And GET /customers/X returns the same values
    @Test
    @DisplayName("should return 200 with updated fields and a later updatedAt when PUT omits the CPF")
    void updateWithoutCpf_returns200() throws Exception {
        JsonNode created = createCustomer(ANA_BODY);
        long id = created.path("id").asLong();
        Instant updatedAtBefore = Instant.parse(created.path("updatedAt").asString());

        String responseBody = updateRequest(id,
                "{\"name\":\"Ana S. Lima\",\"email\":\"ana.lima@example.com\",\"status\":\"INACTIVE\",\"version\":0}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode updated = JSON.readTree(responseBody);
        assertThat(updated.path("id").asLong()).isEqualTo(id);
        assertThat(updated.path("name").asString()).isEqualTo("Ana S. Lima");
        assertThat(updated.path("email").asString()).isEqualTo("ana.lima@example.com");
        assertThat(updated.path("status").asString()).isEqualTo("INACTIVE");
        assertThat(updated.path("cpf").asString()).isEqualTo(ANA_CPF);
        assertThat(updated.path("version").asLong()).isEqualTo(1);
        assertThat(updated.path("createdAt")).isEqualTo(created.path("createdAt"));
        assertThat(Instant.parse(updated.path("updatedAt").asString())).isAfter(updatedAtBefore);
        assertThat(getCustomer(id)).isEqualTo(updated);
    }

    // AC-6 (ticket 03)
    // Given customer X with cpf "52998224725" at version 0
    // When PUT /customers/X {"name":"Ana Souza","email":"ana@example.com","status":"ACTIVE","cpf":"529.982.247-25","version":0}
    // Then the response is 200 with cpf "52998224725" (same CPF after normalization counts as unchanged)
    @Test
    @DisplayName("should return 200 when PUT sends the same CPF in masked form")
    void updateWithUnchangedMaskedCpf_returns200() throws Exception {
        long id = createCustomer(ANA_BODY).path("id").asLong();

        updateRequest(id, "{\"name\":\"Ana Souza\",\"email\":\"ana@example.com\",\"status\":\"ACTIVE\","
                + "\"cpf\":\"529.982.247-25\",\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cpf").value(ANA_CPF))
                .andExpect(jsonPath("$.version").value(1));
    }

    // AC-7 (ticket 03)
    // Given customer X with cpf "52998224725", name "Ana Souza", version 0
    // When PUT /customers/X {"name":"Changed","email":"x@example.com","status":"INACTIVE","cpf":"11144477735","version":0}
    // Then the response is 422 problem+json, "status":422
    //  And GET /customers/X still returns name "Ana Souza", cpf "52998224725", original email and status, "version":0
    @Test
    @DisplayName("should return 422 and change nothing when PUT sends a different CPF")
    void updateWithDifferentCpf_returns422() throws Exception {
        JsonNode created = createCustomer(ANA_BODY);
        long id = created.path("id").asLong();

        expectProblem(updateRequest(id, "{\"name\":\"Changed\",\"email\":\"x@example.com\",\"status\":\"INACTIVE\","
                + "\"cpf\":\"11144477735\",\"version\":0}"), 422);

        JsonNode current = getCustomer(id);
        assertThat(current.path("name").asString()).isEqualTo("Ana Souza");
        assertThat(current.path("cpf").asString()).isEqualTo(ANA_CPF);
        assertThat(current.path("email").asString()).isEqualTo("ana@example.com");
        assertThat(current.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(current.path("version").asLong()).isZero();
        assertThat(current).isEqualTo(created);
    }

    // AC-8 (ticket 03)
    // Given no customer with id 999999
    // When GET /customers/999999
    //      and PUT /customers/999999 {"name":"Ana Souza","email":"ana@example.com","status":"ACTIVE","version":0}
    //      are called
    // Then each response is 404 problem+json with "status":404 and "title":"Customer not found"
    //  (same title for every customer 404 — confirmed by the user)
    //  DELETE is idempotent and returns 204 for an unknown id (user decision): see delete_isIdempotent_returns204.
    @Test
    @DisplayName("should return 404 problem when GET or PUT targets a nonexistent id")
    void unknownId_returns404() throws Exception {
        List<MockHttpServletRequestBuilder> requests = List.of(
                get(customerPath(UNKNOWN_ID)),
                jsonRequest(put(customerPath(UNKNOWN_ID)),
                        "{\"name\":\"Ana Souza\",\"email\":\"ana@example.com\",\"status\":\"ACTIVE\",\"version\":0}"));

        for (MockHttpServletRequestBuilder request : requests) {
            JsonNode problem = expectProblem(mockMvc.perform(request.with(admin())), 404);

            assertThat(problem.path("title").asString()).isEqualTo("Customer not found");
            assertThat(problem.path("instance").asString()).isEqualTo(customerPath(UNKNOWN_ID));
        }
    }

    // AC-8 (ticket 03) — happy-path read
    // Given customer X created with cpf 52998224725
    // When GET /customers/X (as USER)
    // Then the response is 200 with {id:X, name, cpf:"52998224725", email, status, createdAt, updatedAt, version:0}
    @Test
    @DisplayName("should return 200 with the customer when GET targets an existing id")
    void getById_existingCustomer_returns200() throws Exception {
        JsonNode created = createCustomer(ANA_BODY);
        long id = created.path("id").asLong();

        String responseBody = mockMvc.perform(get(customerPath(id)).with(user()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();

        JsonNode body = JSON.readTree(responseBody);
        assertThat(body.path("id").asLong()).isEqualTo(id);
        assertThat(body.path("cpf").asString()).isEqualTo(ANA_CPF);
        assertThat(body.path("version").asLong()).isZero();
        assertThat(body).isEqualTo(created);
    }

    // AC-9 (ticket 03)
    // Given customer X exists
    // When DELETE /customers/X
    // Then the response is 204 with empty body
    //  And a subsequent GET /customers/X returns 404
    //  And no customers row with id X remains (hard delete)
    @Test
    @DisplayName("should return 204 and make the customer unreachable when DELETE targets an existing id")
    void delete_returns204ThenGetReturns404() throws Exception {
        long id = createCustomer(ANA_BODY).path("id").asLong();

        mockMvc.perform(delete(customerPath(id)).with(admin()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        expectProblem(mockMvc.perform(get(customerPath(id)).with(admin())), 404);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM customers WHERE id = ?", Integer.class, id))
                .isZero();
    }

    // AC-10 (ticket 03)
    // Given an ADMIN
    // When POST /customers with body "{\"name\": \"Ana\", " (truncated JSON), Content-Type application/json
    // Then the response is 400 problem+json, "status":400
    //  And the body does not contain "Exception", "at com.", "at org." or "trace"
    @Test
    @DisplayName("should return 400 problem without stack trace when the JSON body is malformed")
    void malformedJson_returns400() throws Exception {
        JsonNode problem = expectProblem(createRequest("{\"name\": \"Ana\", "), 400);

        assertThat(problem.toString()).doesNotContain(STACK_TRACE_MARKERS);
    }

    // AC-10 (ticket 03)
    // Given an ADMIN
    // When GET /customers/abc (and PUT /customers/abc, DELETE /customers/abc)
    // Then each response is 400 problem+json, "status":400, with no stack trace in the body
    @Test
    @DisplayName("should return 400 problem when the id is not numeric")
    void nonNumericId_returns400() throws Exception {
        String path = CUSTOMERS + "/abc";
        List<MockHttpServletRequestBuilder> requests = List.of(
                get(path),
                jsonRequest(put(path), "{\"name\":\"Ana Souza\",\"email\":\"ana@example.com\",\"status\":\"ACTIVE\",\"version\":0}"),
                delete(path));

        for (MockHttpServletRequestBuilder request : requests) {
            JsonNode problem = expectProblem(mockMvc.perform(request.with(admin())), 400);

            assertThat(problem.toString()).doesNotContain(STACK_TRACE_MARKERS);
        }
    }

    // AC-11 (ticket 03) / NFR-12 — deterministic stale update
    // Given customer X created with name "Ana Souza", cpf 52998224725, email "ana@example.com"
    //  And GET /customers/X returns "version":0
    // When PUT /customers/X {"name":"Name A","email":"ana@example.com","status":"ACTIVE","version":0}
    // Then the response is 200 with name "Name A" and "version":1
    // When PUT /customers/X {"name":"Name B","email":"ana@example.com","status":"ACTIVE","version":0} (stale)
    // Then the response is 409 problem+json ("status":409)
    //  And GET /customers/X returns name "Name A" and "version":1 (stale update not applied)
    @Test
    @DisplayName("should return 409 and keep the current data when PUT sends a stale version")
    void staleVersion_returns409() throws Exception {
        long id = createCustomer(ANA_BODY).path("id").asLong();
        assertThat(getCustomer(id).path("version").asLong()).isZero();

        updateRequest(id, "{\"name\":\"Name A\",\"email\":\"ana@example.com\",\"status\":\"ACTIVE\",\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Name A"))
                .andExpect(jsonPath("$.version").value(1));
        expectProblem(updateRequest(id,
                "{\"name\":\"Name B\",\"email\":\"ana@example.com\",\"status\":\"ACTIVE\",\"version\":0}"), 409);

        JsonNode current = getCustomer(id);
        assertThat(current.path("name").asString()).isEqualTo("Name A");
        assertThat(current.path("version").asLong()).isEqualTo(1);
    }

    // AC-11 (ticket 03) / NFR-12
    // Given customer X at version 0
    // When PUT /customers/X {"name":"Ana Souza","email":"ana@example.com","status":"ACTIVE"} (no "version")
    // Then the response is 400 problem+json with exactly 1 errors[] entry, field "version"
    //  And GET /customers/X still returns "version":0
    @Test
    @DisplayName("should return 400 with errors[].field=version when PUT omits the version")
    void putWithoutVersion_returns400() throws Exception {
        long id = createCustomer(ANA_BODY).path("id").asLong();

        JsonNode problem = expectProblem(
                updateRequest(id, "{\"name\":\"Ana Souza\",\"email\":\"ana@example.com\",\"status\":\"ACTIVE\"}"), 400);

        assertThat(errorFields(problem)).containsExactly("version");
        assertThat(getCustomer(id).path("version").asLong()).isZero();
    }

    // AC-11 (ticket 03) / NFR-12 — race where both requests pass the application-level version check
    // Given customer X at version 0
    // When 2 threads concurrently PUT /customers/X, both with "version":0
    //      (A: name "Name A", B: name "Name B"; both email "a@example.com", status ACTIVE)
    // Then exactly one response is 200 and the other is 409 problem+json (never 500, never two 200s)
    //  And the stored name is the winner's value and "version" is 1
    @Test
    @DisplayName("should return 409 for one of two concurrent updates that both send the same version")
    void concurrentUpdatesSameVersion_oneReturns409() throws Exception {
        long id = createCustomer(ANA_BODY).path("id").asLong();
        List<String> names = List.of("Name A", "Name B");
        List<Callable<MockHttpServletResponse>> updates = names.stream()
                .<Callable<MockHttpServletResponse>>map(name -> () -> mockMvc.perform(jsonRequest(put(customerPath(id)),
                                "{\"name\":\"" + name + "\",\"email\":\"a@example.com\",\"status\":\"ACTIVE\",\"version\":0}")
                                .with(admin()))
                        .andReturn().getResponse())
                .toList();

        List<MockHttpServletResponse> responses = runConcurrently(updates);

        assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 409);
        MockHttpServletResponse conflict = responses.stream().filter(response -> response.getStatus() == 409)
                .findFirst().orElseThrow();
        assertThat(MediaType.parseMediaType(conflict.getContentType())).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        String winnerName = responses.stream().filter(response -> response.getStatus() == 200)
                .map(response -> JSON.readTree(contentOf(response)).path("name").asString())
                .findFirst().orElseThrow();
        JsonNode current = getCustomer(id);
        assertThat(current.path("name").asString()).isEqualTo(winnerName).isIn(names);
        assertThat(current.path("version").asLong()).isEqualTo(1);
    }

    // AC-8, AC-9 (ticket 03) — DELETE is idempotent (user decision)
    // Given a customer that an ADMIN already deleted (204), and an id that never existed (999999)
    // When the ADMIN calls DELETE on each of them
    // Then both responses are 204 with an empty body, and GET of the deleted customer still returns 404
    @Test
    @DisplayName("should return 204 when deleting an already-deleted or nonexistent customer")
    void delete_isIdempotent_returns204() throws Exception {
        long id = createCustomer(ANA_BODY).path("id").asLong();
        mockMvc.perform(delete(customerPath(id)).with(admin())).andExpect(status().isNoContent());

        for (long target : List.of(id, UNKNOWN_ID)) {
            MockHttpServletResponse response =
                    mockMvc.perform(delete(customerPath(target)).with(admin())).andReturn().getResponse();
            assertThat(response.getStatus()).as("DELETE %s", customerPath(target)).isEqualTo(204);
            assertThat(contentOf(response)).isEmpty();
        }
        mockMvc.perform(get(customerPath(id)).with(admin())).andExpect(status().isNotFound());
    }

    // AC-9 (ticket 03) — DELETE is idempotent (user decision)
    // Given an existing customer
    // When two ADMIN requests call DELETE /customers/{id} at the same time
    // Then both responses are 204 (never 500), and a later GET returns 404
    @Test
    @DisplayName("should return 204 to both, never 500, when two deletes of the same customer race")
    void concurrentDeletes_bothReturn204() throws Exception {
        long id = createCustomer(ANA_BODY).path("id").asLong();
        Callable<MockHttpServletResponse> deleteCall =
                () -> mockMvc.perform(delete(customerPath(id)).with(admin())).andReturn().getResponse();

        List<MockHttpServletResponse> responses = runConcurrently(List.of(deleteCall, deleteCall));

        assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactly(204, 204);
        mockMvc.perform(get(customerPath(id)).with(admin())).andExpect(status().isNotFound());
    }

    // Edge case (ticket 03)
    // Given an unexpected RuntimeException is thrown while handling GET /customers/{id}
    // When the request completes
    // Then the response is 500 problem+json, "status":500, with a generic detail
    //  And the body does not contain the exception message, class name or stack frames
    @Test
    @DisplayName("should return a generic 500 problem without internals when an unexpected exception occurs")
    void unexpectedException_returnsGeneric500() throws Exception {
        String internalMessage = "connection pool exhausted at db-host-internal:5432";
        doThrow(new IllegalStateException(internalMessage)).when(customerService).findById(anyLong());

        JsonNode problem = expectProblem(mockMvc.perform(get(customerPath(1)).with(admin())), 500);

        assertThat(problem.path("detail").asString()).isEqualTo("An unexpected error occurred.");
        assertThat(problem.toString())
                .doesNotContain(internalMessage, "IllegalStateException", "java.lang", "at com.", "at org.", "trace");
    }

    private ResultActions createRequest(String body) throws Exception {
        return mockMvc.perform(jsonRequest(post(CUSTOMERS), body).with(admin()));
    }

    private ResultActions updateRequest(long id, String body) throws Exception {
        return mockMvc.perform(jsonRequest(put(customerPath(id)), body).with(admin()));
    }

    private JsonNode createCustomer(String body) throws Exception {
        return JSON.readTree(createRequest(body)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode getCustomer(long id) throws Exception {
        return JSON.readTree(mockMvc.perform(get(customerPath(id)).with(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private int statusOf(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(admin())).andReturn().getResponse().getStatus();
    }

    private int customerCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM customers", Integer.class);
    }

    private int customerCountWithCpf(String cpf) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM customers WHERE cpf = ?", Integer.class, cpf);
    }

    /** Asserts the RFC 7807 shape for the given status and returns the parsed body. */
    private static JsonNode expectProblem(ResultActions result, int expectedStatus) throws Exception {
        String body = result
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    private static List<String> errorFields(JsonNode problem) {
        List<String> fields = new ArrayList<>();
        problem.path("errors").forEach(error -> fields.add(error.path("field").asString()));
        return fields;
    }

    private static String errorMessage(JsonNode problem, String field) {
        for (JsonNode error : problem.path("errors")) {
            if (field.equals(error.path("field").asString())) {
                return error.path("message").asString();
            }
        }
        return "";
    }

    @SafeVarargs
    private static List<Integer> runConcurrently(Callable<Integer>... tasks) throws Exception {
        return runConcurrently(List.of(tasks));
    }

    /** Releases all tasks at the same moment to maximise overlap, then waits for every result. */
    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private static String contentOf(MockHttpServletResponse response) {
        try {
            return response.getContentAsString();
        } catch (UnsupportedEncodingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String bodyWithCpf(String cpf) {
        return "{\"name\":\"Ana Souza\",\"cpf\":\"" + cpf + "\",\"email\":\"ana@example.com\"}";
    }

    private static String customerPath(long id) {
        return CUSTOMERS + "/" + id;
    }

    private static MockHttpServletRequestBuilder jsonRequest(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor user() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
