/*
 * Ticket: 01 (GitHub issue #2)
 *
 * Interfaces under test:
 *   - Spring context booted with the "test" profile against a Testcontainers PostgreSQL 16 (+ RabbitMQ)
 *   - GET /actuator/health (public)
 *   - src/main/resources/db/migration/V1__init.sql (Flyway)
 *   - docker-compose.yml, docker/keycloak/*.json (realm export)
 *
 * Note: AC-1's "JaCoCo report is generated" and AC-2's runtime "containers start" are build/ops checks that
 * cannot be asserted from inside the test JVM; they stay manual checks (user decision). The scenarios below cover
 * the parts that are checkable from JUnit.
 *
 * The scenarios that need a running application context live in the nested RunningApplication class so that the
 * static file checks (AC-2, AC-6) still run on machines without Docker; the nested class is skipped there.
 */
package com.datum.clientmanagement.functional;

import com.datum.clientmanagement.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FoundationFunctionalTest {

    private static final Path COMPOSE_FILE = Path.of("docker-compose.yml");
    private static final Path REALM_FILE = Path.of("docker", "keycloak", "client-management-realm.json");
    private static final List<String> DEPENDENCY_SERVICES = List.of("postgres", "rabbitmq", "keycloak", "wiremock");
    private static final int MINIMUM_JAVA_FEATURE_VERSION = 21;

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @Import(TestcontainersConfiguration.class)
    @Testcontainers(disabledWithoutDocker = true)
    class RunningApplication {

        @Autowired
        private DataSource dataSource;

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private JdbcTemplate jdbcTemplate;

        @Autowired
        private Environment environment;

        // AC-1 (ticket 01)
        // Given the "test" profile and a Testcontainers PostgreSQL 16 container (no H2)
        // When the Spring application context starts
        // Then the context loads without error
        //  And the DataSource JDBC URL points at the Testcontainers Postgres (starts with "jdbc:postgresql://")
        //  And the running JVM feature version is >= 21 (Runtime.version().feature())
        @Test
        @DisplayName("should load the application context when started against a Testcontainers Postgres")
        void ac1_contextLoads_againstTestcontainersPostgres() throws SQLException {
            try (Connection connection = dataSource.getConnection()) {
                assertThat(connection.getMetaData().getURL()).startsWith("jdbc:postgresql://");
            }
            assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(MINIMUM_JAVA_FEATURE_VERSION);
        }

        // AC-3 (ticket 01)
        // Given the app is running with Postgres and RabbitMQ (Testcontainers) both up
        // When GET /actuator/health is called with NO Authorization header
        // Then the response status is 200
        //  And the JSON body has "status":"UP"
        //  And body.components contains keys "db" and "rabbit", each with "status":"UP"
        @Test
        @DisplayName("should return 200 UP with db and rabbit components when health is called without a token")
        void ac3_actuatorHealth_returnsUpWithDbAndRabbit() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components.db.status").value("UP"))
                    .andExpect(jsonPath("$.components.rabbit.status").value("UP"));
        }

        // AC-4 (ticket 01)
        // Given the app has started against an empty Postgres
        // When Flyway has run
        // Then flyway_schema_history contains version "1" with description "init" and success = true
        //  And tables "customers", "outbox_events", "processed_events" exist in schema "public"
        //  And customers has columns id, name, name_normalized, cpf, email, status, created_at, updated_at, version
        //  And customers.cpf has a UNIQUE constraint
        //  And outbox_events has columns id, event_id (unique), event_type, aggregate_id, payload (jsonb), created_at,
        //      attempts, last_error, next_attempt_at, sent_at
        //  And processed_events has event_id (uuid, PK), event_type, processed_at
        @Test
        @DisplayName("should create customers, outbox_events and processed_events tables when Flyway runs V1__init")
        void ac4_flywayV1_createsSchema() {
            Map<String, Object> migration = jdbcTemplate.queryForMap(
                    "SELECT description, success FROM flyway_schema_history WHERE version = '1'");
            assertThat(migration).containsEntry("description", "init").containsEntry("success", true);

            assertThat(jdbcTemplate.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class))
                    .contains("customers", "outbox_events", "processed_events");

            assertThat(columnsOf("customers")).contains(
                    "id", "name", "name_normalized", "cpf", "email", "status", "created_at", "updated_at", "version");
            assertThat(constrainedColumns("customers", "UNIQUE")).contains("cpf");

            assertThat(columnsOf("outbox_events")).contains(
                    "id", "event_id", "event_type", "aggregate_id", "payload", "created_at",
                    "attempts", "last_error", "next_attempt_at", "sent_at");
            assertThat(constrainedColumns("outbox_events", "UNIQUE")).contains("event_id");
            assertThat(dataTypeOf("outbox_events", "payload")).isEqualTo("jsonb");

            assertThat(columnsOf("processed_events")).contains("event_id", "event_type", "processed_at");
            assertThat(dataTypeOf("processed_events", "event_id")).isEqualTo("uuid");
            assertThat(constrainedColumns("processed_events", "PRIMARY KEY")).containsExactly("event_id");
        }

        // AC-4 (ticket 01)
        // Given the app context is running
        // When the property "spring.jpa.hibernate.ddl-auto" is read from the Environment
        // Then its value is exactly "validate"
        @Test
        @DisplayName("should run Hibernate with ddl-auto=validate when the app starts")
        void ac4_hibernateDdlAuto_isValidate() {
            assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        }

        private List<String> columnsOf(String table) {
            return jdbcTemplate.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?",
                    String.class, table);
        }

        private String dataTypeOf(String table, String column) {
            return jdbcTemplate.queryForObject(
                    "SELECT data_type FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                    String.class, table, column);
        }

        private List<String> constrainedColumns(String table, String constraintType) {
            return jdbcTemplate.queryForList(
                    "SELECT usage.column_name FROM information_schema.table_constraints constraints "
                            + "JOIN information_schema.constraint_column_usage usage "
                            + "  ON usage.constraint_name = constraints.constraint_name "
                            + " AND usage.table_schema = constraints.table_schema "
                            + "WHERE constraints.table_schema = 'public' AND constraints.table_name = ? "
                            + "  AND constraints.constraint_type = ?",
                    String.class, table, constraintType);
        }
    }

    // AC-2 (ticket 01) — static check of the compose file (runtime start-up stays a manual check — user decision)
    // Given docker-compose.yml at the repo root
    // When it is parsed as YAML
    // Then services "app", "postgres", "rabbitmq", "keycloak" and "wiremock" are defined
    //  And each of postgres, rabbitmq, keycloak, wiremock declares a "healthcheck"
    //  And app.depends_on lists those 4 services, each with "condition: service_healthy"
    @Test
    @DisplayName("should define the five services with healthchecks and healthy-dependency ordering when docker-compose.yml is read")
    void ac2_dockerCompose_definesServicesHealthchecksAndDependsOn() throws IOException {
        Map<String, Object> services = childMap(readYaml(COMPOSE_FILE), "services");

        assertThat(services).containsKeys("app", "postgres", "rabbitmq", "keycloak", "wiremock");
        for (String dependency : DEPENDENCY_SERVICES) {
            assertThat(childMap(services, dependency).get("healthcheck"))
                    .as("healthcheck of %s", dependency)
                    .isNotNull();
        }

        Map<String, Object> appDependsOn = childMap(childMap(services, "app"), "depends_on");
        assertThat(appDependsOn).containsOnlyKeys(DEPENDENCY_SERVICES);
        for (String dependency : DEPENDENCY_SERVICES) {
            assertThat(childMap(appDependsOn, dependency))
                    .as("app.depends_on.%s", dependency)
                    .containsEntry("condition", "service_healthy");
        }
    }

    // AC-6 (ticket 01) — static check of the Keycloak realm import file
    // Given the realm JSON under docker/keycloak/
    // When it is parsed
    // Then realm == "client-management"
    //  And realm roles include "USER" and "ADMIN"
    //  And user "user" exists with realm role USER only
    //  And user "admin" exists with realm roles USER and ADMIN
    //  And a client with publicClient=true and directAccessGrantsEnabled=true exists
    @Test
    @DisplayName("should define realm client-management with USER/ADMIN roles, users and a public client when the realm file is read")
    void ac6_keycloakRealm_isDefinedForImport() throws IOException {
        JsonNode realm = JsonMapper.builder().build().readTree(Files.readString(REALM_FILE));

        assertThat(realm.path("realm").asString()).isEqualTo("client-management");
        assertThat(elements(realm.path("roles").path("realm")).stream().map(role -> role.path("name").asString()))
                .contains("USER", "ADMIN");

        assertThat(realmRolesOfUser(realm, "user")).containsExactly("USER");
        assertThat(realmRolesOfUser(realm, "admin")).containsExactlyInAnyOrder("USER", "ADMIN");

        assertThat(elements(realm.path("clients")))
                .anySatisfy(client -> {
                    assertThat(client.path("publicClient").asBoolean()).isTrue();
                    assertThat(client.path("directAccessGrantsEnabled").asBoolean()).isTrue();
                });
    }

    private static Map<String, Object> readYaml(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return new Yaml().load(reader);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> childMap(Map<String, Object> parent, String key) {
        Object child = parent.get(key);
        assertThat(child).as("YAML node '%s'", key).isInstanceOf(Map.class);
        return (Map<String, Object>) child;
    }

    private static List<JsonNode> elements(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).toList();
    }

    private static List<String> realmRolesOfUser(JsonNode realm, String username) {
        Optional<JsonNode> user = elements(realm.path("users")).stream()
                .filter(candidate -> username.equals(candidate.path("username").asString()))
                .findFirst();
        assertThat(user).as("user '%s'", username).isPresent();
        return elements(user.get().path("realmRoles")).stream().map(JsonNode::asString).toList();
    }
}
