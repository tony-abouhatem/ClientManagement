/*
 * Ticket: 06 (GitHub issue #7)
 *
 * Interfaces under test (do not exist yet):
 *   - POST /customers (ADMIN) triggering CUSTOMER_CREATED after commit
 *   - RabbitMQ: topic exchange "customer.events", routing key "customer.created", durable queue "customer.created"
 *   - Table outbox_events (event_id, event_type, aggregate_id, payload, created_at, attempts, last_error,
 *     next_attempt_at, sent_at) and the @Scheduled outbox relay (interval OUTBOX_RELAY_INTERVAL, default 5 s)
 *   - Message payload: {"eventId":<UUID>,"eventType":"CUSTOMER_CREATED","customerId":<id>,"createdAt":<ISO-8601 UTC>}
 *
 * Integration with Testcontainers Postgres + RabbitMQ. Broker outages are simulated by stopping/pausing the
 * RabbitMQ container (or pointing at a dead port). Timing targets for NFR-7 live in MessagingReliabilityNfrTest.
 */
package com.datum.clientmanagement.functional;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CustomerCreatedEventFunctionalTest {

    // AC-1 (ticket 06)
    // Given RabbitMQ is up and queue "customer.created" is empty
    // When an ADMIN POSTs /customers {"name":"Ana Souza","cpf":"52998224725","email":"ana@example.com"} -> 201 with id X
    // Then within 1 s exactly one message is on "customer.created"
    //  And its message properties have content_type "application/json"
    //  And it was routed via exchange "customer.events" with routing key "customer.created"
    //  And its JSON body is {"eventId":<valid UUID>,"eventType":"CUSTOMER_CREATED","customerId":X,
    //                        "createdAt":<ISO-8601 ending in "Z">}
    //  And outbox_events has 0 rows
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should publish one CUSTOMER_CREATED message when a customer is created and RabbitMQ is up")
    void createCustomer_publishesCustomerCreated() { }

    // AC-2 (ticket 06)
    // Given RabbitMQ is up, queue "customer.created" empty, and an existing customer with cpf 52998224725
    // When POST /customers is called with (a) invalid cpf "52998224726" -> 400,
    //      (b) duplicate cpf "52998224725" -> 409
    // Then after waiting 2 s, queue "customer.created" has 0 messages
    //  And outbox_events has 0 rows
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should publish nothing and write no outbox row when the POST fails validation or hits a duplicate CPF")
    void failedPost_publishesNothing() { }

    // AC-2 (ticket 06)
    // Given RabbitMQ is up and the customer-creation transaction is forced to roll back after the insert
    //       (e.g. a failure injected after save, before commit)
    // When POST /customers {"name":"Rollback Case","cpf":"11144477735","email":"rb@example.com"}
    // Then the response is a 5xx problem
    //  And no customers row with cpf 11144477735 exists
    //  And after 2 s queue "customer.created" has 0 messages and outbox_events has 0 rows
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should publish nothing when the creation transaction rolls back")
    void rolledBackTransaction_publishesNothing() { }

    // AC-3 (ticket 06)
    // Given a test listener on "customer.created" that, on receipt, immediately calls GET /customers/{customerId} as USER
    // When an ADMIN creates customer "Bruno Lima" (cpf 39053344705)
    // Then the listener's GET returns 200 with cpf "39053344705" (never 404)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should let a consumer read the customer when it receives CUSTOMER_CREATED")
    void publishAfterCommit_customerIsReadable() { }

    // AC-4 (ticket 06) / NFR-10
    // Given RabbitMQ is down
    // When an ADMIN POSTs /customers {"name":"Carla Dias","cpf":"93541134780","email":"carla@example.com"}
    // Then the response is 201 with id X
    //  And outbox_events has exactly 1 row with event_type "CUSTOMER_CREATED", aggregate_id X, sent_at NULL,
    //      and payload.eventId == event_id (a valid UUID)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 201 and store one pending outbox row when RabbitMQ is down")
    void rabbitDown_returns201AndWritesOutbox() { }

    // AC-4 (ticket 06)
    // Given RabbitMQ is up but the publish is nacked / the confirm times out / the message is returned as unroutable
    //       (e.g. exchange missing or confirm timeout set very low)
    // When an ADMIN creates customer cpf 98765432100
    // Then the response is 201
    //  And outbox_events has exactly 1 row for that customer with sent_at NULL
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should store a pending outbox row when the publish is nacked, times out or is returned")
    void nackOrTimeoutOrReturn_writesOutbox() { }

    // AC-5 (ticket 06) / NFR-7
    // Given RabbitMQ was down, 1 pending outbox row exists with event_id E for customer X
    // When RabbitMQ comes back and the relay runs
    // Then within 30 s one message with eventId E (same UUID as the row) and customerId X arrives on "customer.created"
    //  And the outbox row has sent_at NOT NULL
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should republish pending outbox rows with their original eventId when RabbitMQ recovers")
    void relayPublishesPendingRowsAfterRecovery() { }

    // AC-5 (ticket 06)
    // Given 3 pending outbox rows created at t1 < t2 < t3 (customers A, B, C)
    // When RabbitMQ is up and the relay runs
    // Then messages arrive on "customer.created" in order A, B, C (ordered by created_at)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should relay pending rows in created_at order")
    void relayOrdersByCreatedAt() { }

    // AC-6 (ticket 06)
    // Given 1 pending outbox row with attempts 0, last_error NULL, next_attempt_at N0, and RabbitMQ still down
    // When the relay runs once
    // Then the row has attempts 1, last_error NOT NULL (non-empty), next_attempt_at > N0, sent_at NULL
    // When the relay runs a second time after next_attempt_at
    // Then attempts == 2 and the gap (next_attempt_at - now) is greater than after the first failure (backoff grows)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should increment attempts, set last_error and push next_attempt_at when a relay publish fails")
    void relayFailure_updatesAttemptsAndBackoff() { }

    // AC-7 (ticket 06)
    // Given 10 pending outbox rows and RabbitMQ up
    // When 2 relay executions run concurrently (two threads calling the relay method at the same time)
    // Then exactly 10 messages arrive on "customer.created" (each eventId once)
    //  And all 10 rows have sent_at NOT NULL
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should send each outbox row once when two relay runs overlap")
    void overlappingRelays_doNotDoubleSend() { }

    // AC-8 (ticket 06)
    // Given a fresh RabbitMQ with no topology
    // When the app starts
    // Then exchange "customer.events" exists, type topic, durable
    //  And queue "customer.created" exists, durable, bound to "customer.events" with routing key "customer.created"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should declare the durable customer.events exchange and customer.created queue at startup")
    void topologyDeclaredAtStartup() { }
}
