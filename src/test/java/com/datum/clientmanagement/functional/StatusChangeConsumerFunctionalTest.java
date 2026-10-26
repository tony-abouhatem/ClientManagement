/*
 * Ticket: docs/backlog/07-status-change-consumer.md
 *
 * Interfaces under test (do not exist yet):
 *   - AMQP listener on queue "customer.status-change" (bound to exchange "customer.events",
 *     routing key "customer.status.change", DLX "customer.dlx" -> queue "customer.status-change.dlq")
 *   - Payload: {"eventId":<UUID>,"eventType":"CUSTOMER_STATUS_CHANGE","customerId":<id>,"status":"ACTIVE|INACTIVE"}
 *   - Table processed_events(event_id PK, event_type, processed_at)
 *
 * Integration with Testcontainers Postgres + RabbitMQ. Messages are published with RabbitTemplate to exchange
 * "customer.events", routing key "customer.status.change". "Eventually" below means polling up to 5 s.
 * Retry-count and timing targets for NFR-8/NFR-9 live in MessagingReliabilityNfrTest.
 */
package com.datum.clientmanagement.functional;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StatusChangeConsumerFunctionalTest {

    // AC-1 (ticket 07)
    // Given customer X (cpf 52998224725) with status ACTIVE
    // When {"eventId":"3f1c2a9e-6b1d-4f6e-9a0b-1c2d3e4f5a60","eventType":"CUSTOMER_STATUS_CHANGE","customerId":X,"status":"INACTIVE"}
    //      is published
    // Then eventually GET /customers/X returns status "INACTIVE"
    //  And processed_events has exactly 1 row with event_id 3f1c2a9e-6b1d-4f6e-9a0b-1c2d3e4f5a60 and event_type "CUSTOMER_STATUS_CHANGE"
    //  And "customer.status-change.dlq" has 0 messages
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should set the customer INACTIVE and record the eventId when a valid status change is consumed")
    void ac1_validStatusChange_isApplied() { }

    // AC-2 (ticket 07) / NFR-8
    // Given customer X ACTIVE
    // When the same message (eventId "7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d", status INACTIVE) is published 3 times
    // Then eventually customer X is INACTIVE
    //  And processed_events has exactly 1 row for that eventId
    //  And queue "customer.status-change" is empty (all 3 acked) and the DLQ has 0 messages
    //  And the customer's version advanced by exactly 1
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should apply the change once and ack every delivery when the same message arrives 3 times")
    void ac2_duplicateDeliveries_appliedOnce() { }

    // AC-3 (ticket 07)
    // Given customer X ACTIVE
    // When message E1 (eventId "11111111-1111-4111-8111-111111111111", status INACTIVE) is consumed
    //  And then message E2 (eventId "22222222-2222-4222-8222-222222222222", status ACTIVE) is consumed
    //  And then E1 is delivered again
    // Then customer X ends with status ACTIVE (E1 replay does not revert it)
    //  And processed_events has exactly 2 rows (E1, E2)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should not revert the status when an old eventId is redelivered after a newer change")
    void ac3_replayedOldEvent_doesNotRevert() { }

    // AC-4 (ticket 07) / NFR-9
    // Given customer X ACTIVE
    // When each of these is published separately:
    //   (a) body "{not json"
    //   (b) {"eventType":"CUSTOMER_STATUS_CHANGE","customerId":X,"status":"INACTIVE"}                  (no eventId)
    //   (c) {"eventId":"<uuid>","eventType":"CUSTOMER_STATUS_CHANGE","status":"INACTIVE"}             (no customerId)
    //   (d) {"eventId":"<uuid>","eventType":"CUSTOMER_STATUS_CHANGE","customerId":X}                   (no status)
    //   (e) {"eventId":"<uuid>","eventType":"CUSTOMER_STATUS_CHANGE","customerId":X,"status":"BLOCKED"} (invalid status)
    //   (f) {"eventId":"<uuid>","eventType":"CUSTOMER_CREATED","customerId":X,"status":"INACTIVE"}     (wrong eventType)
    // Then each message eventually lands on "customer.status-change.dlq"
    //  And the listener processed each one exactly once (no retry)
    //  And customer X is still ACTIVE and processed_events has 0 rows
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should send the message straight to the DLQ when it is malformed, incomplete, has an invalid status or wrong eventType")
    void ac4_invalidMessage_goesToDlqWithoutRetry() { }

    // AC-5 (ticket 07) / NFR-9
    // Given no customer with id 999999
    // When {"eventId":"44444444-4444-4444-8444-444444444444","eventType":"CUSTOMER_STATUS_CHANGE","customerId":999999,"status":"INACTIVE"}
    //      is published
    // Then the message eventually lands on "customer.status-change.dlq" with no retry
    //  And processed_events has no row with that eventId
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should send the message to the DLQ and record nothing when the customerId is unknown")
    void ac5_unknownCustomer_goesToDlq() { }

    // AC-6 (ticket 07) / NFR-9
    // Given customer X ACTIVE and the processing step throws a transient exception (e.g. DataAccessResourceFailureException)
    //       on EVERY attempt
    // When a valid INACTIVE message (eventId "55555555-5555-4555-8555-555555555555") is published
    // Then the processing step is invoked exactly 3 times in total (first delivery + 2 retries), never a 4th
    //  And the message then lands on "customer.status-change.dlq" (exactly 1 message there)
    //  And customer X is still ACTIVE and processed_events has no row for that eventId
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should try exactly 3 times then send to the DLQ when a transient failure persists")
    void ac6_persistentTransientFailure_goesToDlqAfter3Attempts() { }

    // AC-6 (ticket 07)
    // Given customer X ACTIVE and the processing step throws a transient exception on the 1st and 2nd attempts,
    //       and succeeds on the 3rd (last allowed) attempt
    // When a valid INACTIVE message (eventId "66666666-6666-4666-8666-666666666666") is published
    // Then the processing step is invoked exactly 3 times
    //  And customer X eventually becomes INACTIVE, its version advanced by exactly 1
    //  And processed_events has exactly 1 row for that eventId and the DLQ has 0 messages
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should apply the change once when the third and last attempt succeeds after two transient failures")
    void ac6_transientFailureRecoversOnThirdAttempt_appliedOnce() { }

    // AC-7 (ticket 07)
    // Given customer X already INACTIVE with version V
    // When {"eventId":"77777777-7777-4777-8777-777777777777","eventType":"CUSTOMER_STATUS_CHANGE","customerId":X,"status":"INACTIVE"}
    //      is published
    // Then the message is acked (queue empty, DLQ empty)
    //  And processed_events has 1 row for that eventId
    //  And customer X is still INACTIVE
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should record the event and ack as a no-op when the status already matches")
    void ac7_sameStatus_isNoOpButRecorded() { }

    // AC-8 (ticket 07)
    // Given a fresh RabbitMQ with no topology
    // When the app starts
    // Then queue "customer.status-change" exists, durable, with argument x-dead-letter-exchange == "customer.dlx"
    //  And it is bound to exchange "customer.events" with routing key "customer.status.change"
    //  And exchange "customer.dlx" exists and queue "customer.status-change.dlq" exists, durable, bound to it
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should declare and bind the status-change queue with DLX and the DLQ at startup")
    void ac8_topologyDeclaredAtStartup() { }

    // Edge case (ticket 07)
    // Given customer X ACTIVE
    // When the same message (eventId "88888888-8888-4888-8888-888888888888", status INACTIVE) is delivered twice
    //      and both deliveries are processed concurrently (listener concurrency >= 2)
    // Then processed_events has exactly 1 row for that eventId, customer X is INACTIVE,
    //      both deliveries are acked and the DLQ has 0 messages
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should apply once and ack both when two duplicate deliveries are processed concurrently")
    void edge_concurrentDuplicates_appliedOnce() { }

    // Edge case (ticket 07)
    // Given customer X ACTIVE
    // When a valid INACTIVE message (eventId "99999999-9999-4999-8999-999999999999") is published WITHOUT a content_type header
    // Then customer X eventually becomes INACTIVE and the DLQ has 0 messages
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should parse the body as JSON when the content_type header is missing")
    void edge_missingContentType_isParsedAsJson() { }
}
