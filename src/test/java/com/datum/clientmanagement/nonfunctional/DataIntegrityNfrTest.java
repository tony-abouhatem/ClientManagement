/*
 * NFR-12 (docs/PRODUCT.md) plus data-integrity constraints from docs/ARCHITECTURE.md;
 * tickets docs/backlog/01, 03, 06, 07.
 *
 * Interfaces under test: Flyway schema (V1__init.sql) on Testcontainers Postgres, PUT /customers/{id}.
 * Scenarios here act directly on the database (JdbcTemplate) to prove constraints exist at DB level,
 * independent of application checks.
 */
package com.datum.clientmanagement.nonfunctional;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DataIntegrityNfrTest {

    // NFR-12:consistency — deterministic stale update, verified at row level
    // Given customer X (cpf 52998224725, name "Ana Souza") and GET /customers/X returns "version":0
    // When PUT /customers/X {"name":"A","email":"ana@example.com","status":"ACTIVE","version":0} -> 200, "version":1
    //  And the customers row is read via SQL (snapshot S1: name, email, status, updated_at, version)
    //  And PUT /customers/X {"name":"B","email":"b@example.com","status":"INACTIVE","version":0} is sent (stale)
    // Then the second response is 409 problem+json with "status":409 and "instance":"/customers/X"
    //  And the customers row read via SQL equals S1 exactly (name "A", version 1, updated_at unchanged)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should reject a stale-version PUT with 409 and leave the stored row untouched")
    void nfr12_staleUpdate_returns409() { }

    // NFR:data-integrity
    // Given a customers row with cpf "52998224725"
    // When a second row with cpf "52998224725" is inserted directly via SQL
    // Then the insert fails with a unique-constraint violation (SQLState 23505)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should reject a duplicate CPF at database level when inserted directly")
    void dataIntegrity_cpfUniqueConstraint() { }

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
    @Disabled("pending: implementer")
    @DisplayName("should reject null required columns in customers at database level")
    void dataIntegrity_customersNotNullColumns() { }

    // NFR:data-integrity — ticket 04 support
    // Given an ADMIN creates "João da Silva" and then PUTs name "Élise Março"
    // When the customers row is read via SQL
    // Then name_normalized is "joao da silva" after create and "elise marco" after update
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should keep name_normalized lowercased and accent-free when a customer is created or renamed")
    void dataIntegrity_nameNormalizedMaintained() { }
}
