/*
 * NFR-7, NFR-8, NFR-9 (docs/PRODUCT.md); tickets 06 and 07 (GitHub issues #7, #8).
 *
 * Interfaces under test (do not exist yet): CUSTOMER_CREATED publisher + outbox relay, CUSTOMER_STATUS_CHANGE
 * listener, queues "customer.created", "customer.status-change", "customer.status-change.dlq".
 * Testcontainers Postgres + RabbitMQ; RabbitMQ outages simulated by pausing/stopping the container.
 */
package com.datum.clientmanagement.nonfunctional;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MessagingReliabilityNfrTest {

    // NFR-7:reliability (latency, happy path)
    // Given RabbitMQ is up
    // When an ADMIN creates a customer (cpf 52998224725) and the 201 is received at time T
    // Then the CUSTOMER_CREATED message for that customer is on "customer.created" by T + 1000 ms
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should publish CUSTOMER_CREATED within 1 second of commit when RabbitMQ is up")
    void publishWithin1s() { }

    // NFR-7:reliability (outage recovery)
    // Given RabbitMQ is down and the relay interval is the default 5 s
    // When an ADMIN creates 3 customers (cpfs 11144477735, 39053344705, 93541134780) — each returns 201
    //  And RabbitMQ is restarted at time R
    // Then all 3 CUSTOMER_CREATED messages are on "customer.created" by R + 30 s
    //  And all 3 outbox rows have sent_at NOT NULL
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should deliver all events within 30 seconds after RabbitMQ recovers from an outage")
    void deliverWithin30sAfterRecovery() { }

    // NFR-7:config
    // Given OUTBOX_RELAY_INTERVAL overridden to 1s (test property)
    // When a pending outbox row exists and RabbitMQ is up
    // Then the row is sent within 3 s (proving the interval is configurable, not hard-coded to 5 s)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should honour a configured relay interval when OUTBOX_RELAY_INTERVAL is overridden")
    void relayIntervalIsConfigurable() { }

    // NFR-8:idempotency
    // Given customer X ACTIVE
    // When the same CUSTOMER_STATUS_CHANGE (eventId "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", status INACTIVE) is
    //      published N = 10 times
    // Then customer X is INACTIVE, its version advanced by exactly 1
    //  And processed_events has exactly 1 row for that eventId
    //  And the DLQ is empty
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should apply exactly one change and one processed_events row when the same eventId arrives 10 times")
    void sameEventIdNTimes_appliedOnce() { }

    // NFR-8:idempotency (across restarts)
    // Given eventId "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb" was processed and the listener container is then stopped and restarted
    // When the same message is delivered again
    // Then it is acked as a duplicate and processed_events still has 1 row for it
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should still detect a duplicate eventId after the listener restarts")
    void dedupSurvivesRestart() { }

    // NFR-9:reliability (transient retry) — 3 attempts in total = first delivery + 2 retries
    // Given customer X ACTIVE and a processing step that always throws a transient DB exception
    //       (e.g. DataAccessResourceFailureException), recording the timestamp of each invocation
    // When a valid status-change message (eventId "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", status INACTIVE) is published
    // Then the processing step is invoked exactly 3 times (no 4th invocation within 5 s after the 3rd)
    //  And gap(2nd->3rd) > gap(1st->2nd) > 0 ms (exponential backoff)
    //  And the message then lands on "customer.status-change.dlq" exactly once
    //  And customer X is still ACTIVE and processed_events has no row for that eventId
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should attempt a transient failure exactly 3 times with growing backoff and then dead-letter it")
    void transientFailure_retriedThenDlq() { }

    // NFR-9:reliability (no retry for poison messages)
    // Given a counting hook on the listener
    // When "{not json", a message with status "BLOCKED", and one with unknown customerId 999999 are published
    // Then each lands on "customer.status-change.dlq" after exactly 1 listener invocation (0 retries)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should dead-letter malformed, invalid-status and unknown-customer messages with zero retries")
    void poisonMessages_zeroRetries() { }
}
