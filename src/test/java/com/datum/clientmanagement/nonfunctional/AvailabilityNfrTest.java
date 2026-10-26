/*
 * NFR-10 (docs/PRODUCT.md); tickets 01 and 06 (GitHub issues #2, #7).
 *
 * Interfaces under test: app startup, /actuator/health, customer CRUD endpoints.
 * Testcontainers Postgres up; RabbitMQ and/or Score (WireMock) deliberately absent (properties point at closed ports).
 */
package com.datum.clientmanagement.nonfunctional;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AvailabilityNfrTest {

    // NFR-10:availability
    // Given Postgres is up and spring.rabbitmq.port points at a closed port (RabbitMQ down at startup)
    // When the application context starts
    // Then startup succeeds (no exception, context active)
    //  And GET /actuator/health returns 503 with "status":"DOWN" and components.rabbit.status == "DOWN"
    //      while components.db.status == "UP"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should start and report rabbit DOWN in health when RabbitMQ is unavailable at startup")
    void startsWithoutRabbit_healthShowsRabbitDown() { }

    // NFR-10:availability
    // Given the app started with RabbitMQ down
    // When an ADMIN POSTs /customers {"name":"Ana Souza","cpf":"52998224725","email":"ana@example.com"},
    //      then GET, PUT and DELETE on that customer
    // Then the responses are 201, 200, 200 and 204 respectively
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should keep CRUD working when RabbitMQ is down")
    void crudWorksWithoutRabbit() { }

    // NFR-10:availability
    // Given Postgres and RabbitMQ are up and SCORE_BASE_URL points at a closed port (WireMock down)
    // When the application context starts
    // Then startup succeeds, POST/GET /customers work (201/200)
    //  And GET /customers/{id}/score returns 503 problem+json
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should start and keep CRUD working when the Score service is down")
    void startsWithoutScoreService() { }
}
