/*
 * NFR-7, NFR-8, NFR-9 (docs/PRODUCT.md); tickets 06 and 07 (GitHub issues #7, #8).
 *
 * Interfaces under test (do not exist yet): CUSTOMER_CREATED publisher + outbox relay, CUSTOMER_STATUS_CHANGE
 * listener, queues "customer.created", "customer.status-change", "customer.status-change.dlq".
 * Testcontainers Postgres + RabbitMQ; RabbitMQ outages simulated by pausing/stopping the container.
 */
package com.datum.clientmanagement.nonfunctional;

import com.datum.clientmanagement.RabbitCtl;
import com.datum.clientmanagement.RabbitOutage;
import com.datum.clientmanagement.RestartableRabbitConfiguration;
import com.datum.clientmanagement.StatusChangeFixture;
import com.datum.clientmanagement.messaging.MessagingTopology;
import com.datum.clientmanagement.messaging.StatusChangeMessageParser;
import com.datum.clientmanagement.messaging.StatusChangeProcessor;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.datum.clientmanagement.StatusChangeFixture.statusChange;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// A 100 ms first retry backoff (production: 1 s) keeps the retry test fast; the attempt count is unchanged.
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.retry.initial-interval=100ms")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RestartableRabbitConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"}) // IDE has no database; the SQL runs against Testcontainers Postgres
class MessagingReliabilityNfrTest {

    private static final String QUEUE = MessagingTopology.CUSTOMER_CREATED_QUEUE;
    private static final long PUBLISH_LATENCY_MS = 1000;
    private static final Duration RECOVERY_DEADLINE = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    private static final List<String> OUTAGE_CPFS = List.of("11144477735", "39053344705", "93541134780");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String STATUS_CHANGE_QUEUE = MessagingTopology.STATUS_CHANGE_QUEUE;
    private static final String STATUS_CHANGE_DLQ = MessagingTopology.STATUS_CHANGE_DEAD_LETTER_QUEUE;
    private static final int SAME_EVENT_DELIVERIES = 10;
    private static final long UNKNOWN_CUSTOMER_ID = 999_999L;
    private static final long VERIFY_TIMEOUT_MS = 10_000;
    private static final Duration EVENTUALLY = Duration.ofSeconds(5);
    // 3 attempts with 100 ms and 200 ms backoff in between (test property), plus processing time.
    private static final Duration RETRIES_EXHAUSTED = Duration.ofSeconds(15);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitOutage rabbitOutage;

    @Autowired
    private RabbitMQContainer rabbitContainer;

    @Autowired
    private RabbitListenerEndpointRegistry listenerContainers;

    // Counting hook: every listener invocation (first delivery or retry) parses the message first.
    @MockitoSpyBean
    private StatusChangeMessageParser statusChangeParser;

    @MockitoSpyBean
    private StatusChangeProcessor statusChangeProcessor;

    private StatusChangeFixture fixture;

    @BeforeEach
    void cleanState() {
        fixture = new StatusChangeFixture(jdbcTemplate, rabbitTemplate);
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM processed_events");
        jdbcTemplate.update("DELETE FROM customers");
        amqpAdmin.purgeQueue(QUEUE, false);
        amqpAdmin.purgeQueue(STATUS_CHANGE_QUEUE, false);
        amqpAdmin.purgeQueue(STATUS_CHANGE_DLQ, false);
    }

    @AfterEach
    void restoreBroker() {
        if (!rabbitOutage.isRunning()) {
            rabbitOutage.start();
        }
        rabbitOutage.awaitReady();
    }

    // NFR-7:reliability (latency, happy path)
    // Given RabbitMQ is up
    // When an ADMIN creates a customer (cpf 52998224725) and the 201 is received at time T
    // Then the CUSTOMER_CREATED message for that customer is on "customer.created" by T + 1000 ms
    @Test
    @DisplayName("should publish CUSTOMER_CREATED within 1 second of commit when RabbitMQ is up")
    void publishWithin1s() throws Exception {
        long customerId = createCustomer("52998224725");
        long receivedAt = System.nanoTime();

        Message message = rabbitTemplate.receive(QUEUE, PUBLISH_LATENCY_MS);

        long waitedMs = Duration.ofNanos(System.nanoTime() - receivedAt).toMillis();
        assertThat(message).as("CUSTOMER_CREATED message").isNotNull();
        assertThat(customerIdOf(message)).isEqualTo(customerId);
        assertThat(waitedMs).isLessThanOrEqualTo(PUBLISH_LATENCY_MS);
    }

    // NFR-7:reliability (outage recovery)
    // Given RabbitMQ is down and the relay interval is the default 5 s
    // When an ADMIN creates 3 customers (cpfs 11144477735, 39053344705, 93541134780) — each returns 201
    //  And RabbitMQ is restarted at time R
    // Then all 3 CUSTOMER_CREATED messages are on "customer.created" by R + 30 s
    //  And all 3 outbox rows have sent_at NOT NULL
    @Test
    @DisplayName("should deliver all events within 30 seconds after RabbitMQ recovers from an outage")
    void deliverWithin30sAfterRecovery() throws Exception {
        rabbitOutage.stop();
        List<Long> customerIds = new ArrayList<>();
        for (String cpf : OUTAGE_CPFS) {
            customerIds.add(createCustomer(cpf));
        }

        rabbitOutage.start();
        List<Message> received = new ArrayList<>();
        Awaitility.await()
                .atMost(RECOVERY_DEADLINE)
                .pollInterval(POLL_INTERVAL)
                .ignoreExceptionsInstanceOf(AmqpException.class)
                .untilAsserted(() -> {
                    drainQueueInto(received);
                    assertThat(received).hasSize(OUTAGE_CPFS.size());
                    assertThat(sentRowCount()).isEqualTo(OUTAGE_CPFS.size());
                });

        assertThat(received).extracting(MessagingReliabilityNfrTest::customerIdOf)
                .containsExactlyInAnyOrderElementsOf(customerIds);
    }

    // The interval is read once at startup, so overriding it needs its own application context.
    @Nested
    @TestPropertySource(properties = "app.outbox.relay-interval=1s")
    class ConfiguredRelayInterval {

        private static final Duration SENT_DEADLINE = Duration.ofSeconds(3);

        @Autowired
        private JdbcTemplate intervalJdbcTemplate;

        // NFR-7:config
        // Given OUTBOX_RELAY_INTERVAL overridden to 1s (test property)
        // When a pending outbox row exists and RabbitMQ is up
        // Then the row is sent within 3 s (proving the interval is configurable, not hard-coded to 5 s)
        @Test
        @DisplayName("should honour a configured relay interval when OUTBOX_RELAY_INTERVAL is overridden")
        void relayIntervalIsConfigurable() {
            intervalJdbcTemplate.update("DELETE FROM outbox_events");
            UUID eventId = UUID.randomUUID();
            Timestamp now = Timestamp.from(Instant.now());
            intervalJdbcTemplate.update("INSERT INTO outbox_events"
                    + " (event_id, event_type, aggregate_id, payload, created_at, next_attempt_at)"
                    + " VALUES (?, 'CUSTOMER_CREATED', 1, CAST(? AS jsonb), ?, ?)",
                    eventId, "{\"eventId\":\"" + eventId + "\",\"eventType\":\"CUSTOMER_CREATED\",\"customerId\":1,"
                            + "\"createdAt\":\"" + now.toInstant() + "\"}", now, now);

            Awaitility.await().atMost(SENT_DEADLINE).pollInterval(POLL_INTERVAL).untilAsserted(() ->
                    assertThat(intervalJdbcTemplate.queryForObject(
                            "SELECT sent_at FROM outbox_events WHERE event_id = ?", Timestamp.class, eventId))
                            .isNotNull());
        }
    }

    // NFR-8:idempotency
    // Given customer X ACTIVE
    // When the same CUSTOMER_STATUS_CHANGE (eventId "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", status INACTIVE) is
    //      published N = 10 times
    // Then customer X is INACTIVE, its version advanced by exactly 1
    //  And processed_events has exactly 1 row for that eventId
    //  And the DLQ is empty
    @Test
    @DisplayName("should apply exactly one change and one processed_events row when the same eventId arrives 10 times")
    void sameEventIdNTimes_appliedOnce() throws Exception {
        long customerId = createCustomer("52998224725");
        UUID eventId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        String message = statusChange(eventId, customerId, "INACTIVE");

        for (int delivery = 0; delivery < SAME_EVENT_DELIVERIES; delivery++) {
            fixture.publish(message);
        }

        verify(statusChangeProcessor, timeout(VERIFY_TIMEOUT_MS).times(SAME_EVENT_DELIVERIES)).process(any());
        awaitAllAcked();
        assertThat(fixture.statusOf(customerId)).isEqualTo("INACTIVE");
        assertThat(fixture.versionOf(customerId)).isEqualTo(1);
        assertThat(fixture.processedRows(eventId)).isEqualTo(1);
        assertThat(fixture.deadLetterCount()).isZero();
    }

    // NFR-8:idempotency (across restarts)
    // Given eventId "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb" was processed and the listener container is then stopped and restarted
    // When the same message is delivered again
    // Then it is acked as a duplicate and processed_events still has 1 row for it
    @Test
    @DisplayName("should still detect a duplicate eventId after the listener restarts")
    void dedupSurvivesRestart() throws Exception {
        long customerId = createCustomer("52998224725");
        UUID eventId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        String message = statusChange(eventId, customerId, "INACTIVE");
        fixture.publish(message);
        Awaitility.await().atMost(EVENTUALLY).untilAsserted(() -> assertThat(fixture.processedRows(eventId)).isEqualTo(1));
        awaitAllAcked();

        listenerContainers.stop();
        listenerContainers.start();
        fixture.publish(message);

        verify(statusChangeProcessor, timeout(VERIFY_TIMEOUT_MS).times(2)).process(any());
        awaitAllAcked();
        assertThat(fixture.processedRows(eventId)).isEqualTo(1);
        assertThat(fixture.versionOf(customerId)).isEqualTo(1);
        assertThat(fixture.deadLetterCount()).isZero();
    }

    // NFR-9:reliability (transient retry) — 3 attempts in total = first delivery + 2 retries
    // Given customer X ACTIVE and a processing step that always throws a transient DB exception
    //       (e.g. DataAccessResourceFailureException), recording the timestamp of each invocation
    // When a valid status-change message (eventId "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", status INACTIVE) is published
    // Then the processing step is invoked exactly 3 times (none after the message is dead-lettered)
    //  And gap(2nd->3rd) > gap(1st->2nd) > 0 ms (exponential backoff)
    //  And the message then lands on "customer.status-change.dlq" exactly once
    //  And customer X is still ACTIVE and processed_events has no row for that eventId
    @Test
    @DisplayName("should attempt a transient failure exactly 3 times with growing backoff and then dead-letter it")
    void transientFailure_retriedThenDlq() throws Exception {
        long customerId = createCustomer("52998224725");
        UUID eventId = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
        List<Long> attemptNanos = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            attemptNanos.add(System.nanoTime());
            throw new DataAccessResourceFailureException("simulated database outage");
        }).when(statusChangeProcessor).process(any());

        fixture.publish(statusChange(eventId, customerId, "INACTIVE"));

        Awaitility.await().atMost(RETRIES_EXHAUSTED).pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> assertThat(fixture.deadLetterCount()).isEqualTo(1));
        // The message is dead-lettered only after the last attempt, so no 4th attempt can follow.
        assertThat(attemptNanos).hasSize(3);
        long firstGap = attemptNanos.get(1) - attemptNanos.get(0);
        long secondGap = attemptNanos.get(2) - attemptNanos.get(1);
        assertThat(firstGap).isPositive();
        assertThat(secondGap).isGreaterThan(firstGap);
        assertThat(fixture.deadLetterCount()).isEqualTo(1);
        assertThat(fixture.statusOf(customerId)).isEqualTo("ACTIVE");
        assertThat(fixture.processedRows(eventId)).isZero();
    }

    // NFR-9:reliability (no retry for poison messages)
    // Given a counting hook on the listener
    // When "{not json", a message with status "BLOCKED", and one with unknown customerId 999999 are published
    // Then each lands on "customer.status-change.dlq" after exactly 1 listener invocation (0 retries)
    @Test
    @DisplayName("should dead-letter malformed, invalid-status and unknown-customer messages with zero retries")
    void poisonMessages_zeroRetries() throws Exception {
        long customerId = createCustomer("52998224725");
        List<String> poisonMessages = List.of(
                "{not json",
                statusChange(UUID.randomUUID(), customerId, "BLOCKED"),
                statusChange(UUID.randomUUID(), UNKNOWN_CUSTOMER_ID, "INACTIVE"));

        poisonMessages.forEach(fixture::publish);

        // A retry would parse a message again before dead-lettering it, so one parse per message proves none.
        Awaitility.await().atMost(EVENTUALLY).pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> assertThat(fixture.deadLetterCount()).isEqualTo(poisonMessages.size()));
        verify(statusChangeParser, times(poisonMessages.size())).parse(any());
        // Only the unknown customer gets past parsing, and it is processed once.
        verify(statusChangeProcessor, times(1)).process(any());
    }

    private long createCustomer(String cpf) throws Exception {
        String body = mockMvc.perform(post("/customers")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ana Souza\",\"cpf\":\"" + cpf + "\",\"email\":\"ana@example.com\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).path("id").asLong();
    }

    private void awaitAllAcked() {
        Awaitility.await().atMost(EVENTUALLY).pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> assertThat(RabbitCtl.messageCount(rabbitContainer, STATUS_CHANGE_QUEUE)).isZero());
    }

    private void drainQueueInto(List<Message> received) {
        Message message = rabbitTemplate.receive(QUEUE);
        while (message != null) {
            received.add(message);
            message = rabbitTemplate.receive(QUEUE);
        }
    }

    private long sentRowCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events WHERE sent_at IS NOT NULL", Long.class);
    }

    private static long customerIdOf(Message message) {
        return JSON.readTree(new String(message.getBody(), StandardCharsets.UTF_8)).path("customerId").asLong();
    }
}
