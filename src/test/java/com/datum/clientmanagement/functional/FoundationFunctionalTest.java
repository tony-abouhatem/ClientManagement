/*
 * Ticket: docs/backlog/01-foundation-and-local-stack.md
 *
 * Interfaces under test (do not exist yet):
 *   - Spring context booted with the "test" profile against a Testcontainers PostgreSQL 16 (+ RabbitMQ)
 *   - GET /actuator/health (public)
 *   - src/main/resources/db/migration/V1__init.sql (Flyway)
 *   - docker-compose.yml, docker/keycloak/*.json (realm export)
 *
 * Note: AC-1's "JaCoCo report is generated" and AC-2's runtime "containers start" are build/ops checks that
 * cannot be asserted from inside the test JVM; they stay manual checks (user decision). The scenarios below cover
 * the parts that are checkable from JUnit.
 */
package com.datum.clientmanagement.functional;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FoundationFunctionalTest {

    // AC-1 (ticket 01)
    // Given the "test" profile and a Testcontainers PostgreSQL 16 container (no H2)
    // When the Spring application context starts
    // Then the context loads without error
    //  And the DataSource JDBC URL points at the Testcontainers Postgres (starts with "jdbc:postgresql://")
    //  And the running JVM feature version is >= 21 (Runtime.version().feature())
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should load the application context when started against a Testcontainers Postgres")
    void ac1_contextLoads_againstTestcontainersPostgres() { }

    // AC-3 (ticket 01)
    // Given the app is running with Postgres and RabbitMQ (Testcontainers) both up
    // When GET /actuator/health is called with NO Authorization header
    // Then the response status is 200
    //  And the JSON body has "status":"UP"
    //  And body.components contains keys "db" and "rabbit", each with "status":"UP"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 200 UP with db and rabbit components when health is called without a token")
    void ac3_actuatorHealth_returnsUpWithDbAndRabbit() { }

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
    @Disabled("pending: implementer")
    @DisplayName("should create customers, outbox_events and processed_events tables when Flyway runs V1__init")
    void ac4_flywayV1_createsSchema() { }

    // AC-4 (ticket 01)
    // Given the app context is running
    // When the property "spring.jpa.hibernate.ddl-auto" is read from the Environment
    // Then its value is exactly "validate"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should run Hibernate with ddl-auto=validate when the app starts")
    void ac4_hibernateDdlAuto_isValidate() { }

    // AC-2 (ticket 01) — static check of the compose file (runtime start-up stays a manual check — user decision)
    // Given docker-compose.yml at the repo root
    // When it is parsed as YAML
    // Then services "app", "postgres", "rabbitmq", "keycloak" and "wiremock" are defined
    //  And each of postgres, rabbitmq, keycloak, wiremock declares a "healthcheck"
    //  And app.depends_on lists those 4 services, each with "condition: service_healthy"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should define the five services with healthchecks and healthy-dependency ordering when docker-compose.yml is read")
    void ac2_dockerCompose_definesServicesHealthchecksAndDependsOn() { }

    // AC-6 (ticket 01) — static check of the Keycloak realm import file
    // Given the realm JSON under docker/keycloak/
    // When it is parsed
    // Then realm == "client-management"
    //  And realm roles include "USER" and "ADMIN"
    //  And user "user" exists with realm role USER only
    //  And user "admin" exists with realm roles USER and ADMIN
    //  And a client with publicClient=true and directAccessGrantsEnabled=true exists
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should define realm client-management with USER/ADMIN roles, users and a public client when the realm file is read")
    void ac6_keycloakRealm_isDefinedForImport() { }
}
