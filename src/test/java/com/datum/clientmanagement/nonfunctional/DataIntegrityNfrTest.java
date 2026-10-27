/*
 * NFR-12 (docs/PRODUCT.md) plus data-integrity constraints from docs/ARCHITECTURE.md;
 * tickets 01, 03, 06, 07 (GitHub issues #2, #4, #7, #8).
 *
 * Interfaces under test: Flyway schema (V1__init.sql) on Testcontainers Postgres, PUT /customers/{id}.
 * Scenarios here act directly on the database (JdbcTemplate) to prove constraints exist at DB level,
 * independent of application checks.
 */
package com.datum.clientmanagement.nonfunctional;

import com.datum.clientmanagement.TestcontainersConfiguration;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
class DataIntegrityNfrTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String NOT_NULL_VIOLATION = "23502";
    private static final String INSERT_CUSTOMER = "INSERT INTO customers "
            + "(name, name_normalized, cpf, email, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())";
    private static final String ROW_SNAPSHOT =
            "SELECT name, email, status, updated_at, version FROM customers WHERE id = ?";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void deleteAllCustomers() {
        jdbcTemplate.update("DELETE FROM customers");
    }

    // NFR-12:consistency — deterministic stale update, verified at row level
    // Given customer X (cpf 52998224725, name "Ana Souza") and GET /customers/X returns "version":0
    // When PUT /customers/X {"name":"A","email":"ana@example.com","status":"ACTIVE","version":0} -> 200, "version":1
    //  And the customers row is read via SQL (snapshot S1: name, email, status, updated_at, version)
    //  And PUT /customers/X {"name":"B","email":"b@example.com","status":"INACTIVE","version":0} is sent (stale)
    // Then the second response is 409 problem+json with "status":409 and "instance":"/customers/X"
    //  And the customers row read via SQL equals S1 exactly (name "A", version 1, updated_at unchanged)
    @Test
    @DisplayName("should reject a stale-version PUT with 409 and leave the stored row untouched")
    void staleUpdate_returns409() throws Exception {
        long id = createCustomer("Ana Souza", "52998224725");
        String path = "/customers/" + id;
        perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0));

        perform(jsonRequest(put(path), "{\"name\":\"A\",\"email\":\"ana@example.com\",\"status\":\"ACTIVE\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        Map<String, Object> snapshot = jdbcTemplate.queryForMap(ROW_SNAPSHOT, id);

        perform(jsonRequest(put(path), "{\"name\":\"B\",\"email\":\"b@example.com\",\"status\":\"INACTIVE\",\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.instance").value(path));

        assertThat(jdbcTemplate.queryForMap(ROW_SNAPSHOT, id)).isEqualTo(snapshot);
        assertThat(snapshot).containsEntry("name", "A").containsEntry("version", 1L);
    }

    // NFR:data-integrity
    // Given a customers row with cpf "52998224725"
    // When a second row with cpf "52998224725" is inserted directly via SQL
    // Then the insert fails with a unique-constraint violation (SQLState 23505)
    @Test
    @DisplayName("should reject a duplicate CPF at database level when inserted directly")
    void dataIntegrity_cpfUniqueConstraint() {
        insertCustomer("Ana Souza", "52998224725", "ana@example.com", "ACTIVE");

        assertThat(sqlStateOf(() -> insertCustomer("Outra Pessoa", "52998224725", "outra@example.com", "ACTIVE")))
                .isEqualTo(UNIQUE_VIOLATION);
    }

    // NFR:data-integrity
    // Given a processed_events row with event_id "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    // When the same event_id is inserted again via SQL
    // Then the insert fails with SQLState 23505 (PK violation)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should reject a duplicate eventId in processed_events at database level")
    void dataIntegrity_processedEventsPrimaryKey() { }

    // NFR:data-integrity
    // Given an outbox_events row with event_id "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
    // When another row with the same event_id is inserted via SQL
    // Then the insert fails with SQLState 23505
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should reject a duplicate eventId in outbox_events at database level")
    void dataIntegrity_outboxEventIdUnique() { }

    // NFR:data-integrity
    // Given the customers table
    // When a row is inserted via SQL with name NULL (and, separately, cpf NULL, email NULL, status NULL)
    // Then each insert fails with SQLState 23502 (not-null violation)
    @Test
    @DisplayName("should reject null required columns in customers at database level")
    void dataIntegrity_customersNotNullColumns() {
        assertThat(sqlStateOf(() -> insertCustomer(null, "52998224725", "ana@example.com", "ACTIVE")))
                .as("name NULL").isEqualTo(NOT_NULL_VIOLATION);
        assertThat(sqlStateOf(() -> insertCustomer("Ana Souza", null, "ana@example.com", "ACTIVE")))
                .as("cpf NULL").isEqualTo(NOT_NULL_VIOLATION);
        assertThat(sqlStateOf(() -> insertCustomer("Ana Souza", "52998224725", null, "ACTIVE")))
                .as("email NULL").isEqualTo(NOT_NULL_VIOLATION);
        assertThat(sqlStateOf(() -> insertCustomer("Ana Souza", "52998224725", "ana@example.com", null)))
                .as("status NULL").isEqualTo(NOT_NULL_VIOLATION);
    }

    // NFR:data-integrity — ticket 04 support
    // Given an ADMIN creates "João da Silva" and then PUTs name "Élise Março"
    // When the customers row is read via SQL
    // Then name_normalized is "joao da silva" after create and "elise marco" after update
    @Test
    @DisplayName("should keep name_normalized lowercased and accent-free when a customer is created or renamed")
    void dataIntegrity_nameNormalizedMaintained() throws Exception {
        long id = createCustomer("João da Silva", "39053344705");
        assertThat(nameNormalizedOf(id)).isEqualTo("joao da silva");

        perform(jsonRequest(put("/customers/" + id),
                "{\"name\":\"Élise Março\",\"email\":\"ana@example.com\",\"status\":\"ACTIVE\",\"version\":0}"))
                .andExpect(status().isOk());

        assertThat(nameNormalizedOf(id)).isEqualTo("elise marco");
    }

    private long createCustomer(String name, String cpf) throws Exception {
        String body = perform(jsonRequest(post("/customers"),
                "{\"name\":\"" + name + "\",\"cpf\":\"" + cpf + "\",\"email\":\"ana@example.com\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).path("id").asLong();
    }

    private String nameNormalizedOf(long id) {
        return jdbcTemplate.queryForObject("SELECT name_normalized FROM customers WHERE id = ?", String.class, id);
    }

    // The name doubles as name_normalized: these rows only exist to exercise the table constraints.
    private void insertCustomer(String name, String cpf, String email, String status) {
        jdbcTemplate.update(INSERT_CUSTOMER, name, name, cpf, email, status);
    }

    private static String sqlStateOf(ThrowingCallable insert) {
        DataAccessException failure = catchThrowableOfType(DataAccessException.class, insert);
        assertThat(failure).as("the insert should be rejected by the database").isNotNull();
        Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(failure);
        assertThat(rootCause).isInstanceOf(SQLException.class);
        return ((SQLException) rootCause).getSQLState();
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private static MockHttpServletRequestBuilder jsonRequest(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
