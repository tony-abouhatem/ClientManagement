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

import com.datum.clientmanagement.RabbitCtl;
import com.datum.clientmanagement.RabbitOutage;
import com.datum.clientmanagement.RestartableRabbitConfiguration;
import com.datum.clientmanagement.event.CustomerCreatedEvent;
import com.datum.clientmanagement.config.MessagingTopology;
import com.datum.clientmanagement.messaging.OutboxRelay;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The relay only runs when a test calls it, so attempts and timings stay deterministic.
@SpringBootTest(properties = "app.outbox.relay-interval=" + CustomerCreatedEventFunctionalTest.MANUAL_RELAY_INTERVAL)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({RestartableRabbitConfiguration.class, CustomerCreatedEventFunctionalTest.CommitFailureInjection.class})
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"}) // IDE has no database; the SQL runs against Testcontainers Postgres
class CustomerCreatedEventFunctionalTest {

    static final String MANUAL_RELAY_INTERVAL = "1h";

    private static final String QUEUE = MessagingTopology.CUSTOMER_CREATED_QUEUE;
    private static final long PUBLISH_WINDOW_MS = 1000;
    private static final long NOTHING_PUBLISHED_WAIT_MS = 2000;
    private static final long CONSUMER_WAIT_MS = 10_000;
    private static final Duration RECOVERY_DEADLINE = Duration.ofSeconds(30);
    private static final Duration RELAY_RETRY_POLL = Duration.ofMillis(500);
    private static final int CONCURRENT_ROWS = 10;
    private static final String INSERT_CUSTOMER = "INSERT INTO customers"
            + " (name, name_normalized, cpf, email, status, created_at, updated_at)"
            + " VALUES (?, ?, ?, 'test@example.com', 'ACTIVE', now(), now()) RETURNING id";
    private static final String INSERT_OUTBOX_ROW = "INSERT INTO outbox_events"
            + " (event_id, event_type, aggregate_id, payload, created_at, next_attempt_at)"
            + " VALUES (?, 'CUSTOMER_CREATED', ?, CAST(? AS jsonb), ?, ?)";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private Binding customerCreatedBinding;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private RabbitOutage rabbitOutage;

    @Autowired
    private RabbitMQContainer rabbitContainer;

    @Autowired
    private CommitFailureInjection commitFailure;

    @BeforeEach
    void cleanState() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM customers");
        amqpAdmin.purgeQueue(QUEUE, false);
        commitFailure.disarm();
    }

    @AfterEach
    void restoreBroker() {
        if (!rabbitOutage.isRunning()) {
            rabbitOutage.start();
        }
        rabbitOutage.awaitReady();
        // Puts back anything a test removed (binding, exchange).
        amqpAdmin.initialize();
    }

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
    @DisplayName("should publish one CUSTOMER_CREATED message when a customer is created and RabbitMQ is up")
    void createCustomer_publishesCustomerCreated() throws Exception {
        long customerId = createCustomer("Ana Souza", "52998224725", "ana@example.com");

        Message message = rabbitTemplate.receive(QUEUE, PUBLISH_WINDOW_MS);

        assertThat(message).as("CUSTOMER_CREATED message within 1 s").isNotNull();
        MessageProperties properties = message.getMessageProperties();
        assertThat(properties.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        assertThat(properties.getReceivedExchange()).isEqualTo("customer.events");
        assertThat(properties.getReceivedRoutingKey()).isEqualTo("customer.created");
        JsonNode body = bodyOf(message);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("eventId", "eventType", "customerId", "createdAt");
        assertThat(UUID.fromString(body.path("eventId").asString())).isNotNull();
        assertThat(body.path("eventType").asString()).isEqualTo("CUSTOMER_CREATED");
        assertThat(body.path("customerId").asLong()).isEqualTo(customerId);
        String createdAt = body.path("createdAt").asString();
        assertThat(createdAt).endsWith("Z");
        assertThat(Instant.parse(createdAt)).isNotNull();
        assertThat(rabbitTemplate.receive(QUEUE)).as("no second message").isNull();
        assertThat(outboxRowCount()).isZero();
    }

    // AC-2 (ticket 06)
    // Given RabbitMQ is up, queue "customer.created" empty, and an existing customer with cpf 52998224725
    // When POST /customers is called with (a) invalid cpf "52998224726" -> 400,
    //      (b) duplicate cpf "52998224725" -> 409
    // Then after waiting 2 s, queue "customer.created" has 0 messages
    //  And outbox_events has 0 rows
    @Test
    @DisplayName("should publish nothing and write no outbox row when the POST fails validation or hits a duplicate CPF")
    void failedPost_publishesNothing() throws Exception {
        insertCustomer("Ana Souza", "52998224725");

        postCustomer("Ana Souza", "52998224726", "ana@example.com").andExpect(status().isBadRequest());
        postCustomer("Ana Souza", "52998224725", "ana@example.com").andExpect(status().isConflict());

        assertThat(rabbitTemplate.receive(QUEUE, NOTHING_PUBLISHED_WAIT_MS)).isNull();
        assertThat(outboxRowCount()).isZero();
    }

    // AC-2 (ticket 06)
    // Given RabbitMQ is up and the customer-creation transaction is forced to roll back after the insert
    //       (e.g. a failure injected after save, before commit)
    // When POST /customers {"name":"Rollback Case","cpf":"11144477735","email":"rb@example.com"}
    // Then the response is a 5xx problem
    //  And no customers row with cpf 11144477735 exists
    //  And after 2 s queue "customer.created" has 0 messages and outbox_events has 0 rows
    @Test
    @DisplayName("should publish nothing when the creation transaction rolls back")
    void rolledBackTransaction_publishesNothing() throws Exception {
        commitFailure.arm();

        postCustomer("Rollback Case", "11144477735", "rb@example.com")
                .andExpect(status().is5xxServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM customers WHERE cpf = ?", Long.class,
                "11144477735")).isZero();
        assertThat(rabbitTemplate.receive(QUEUE, NOTHING_PUBLISHED_WAIT_MS)).isNull();
        assertThat(outboxRowCount()).isZero();
    }

    // AC-3 (ticket 06)
    // Given a test listener on "customer.created" that, on receipt, immediately calls GET /customers/{customerId} as USER
    // When an ADMIN creates customer "Bruno Lima" (cpf 39053344705)
    // Then the listener's GET returns 200 with cpf "39053344705" (never 404)
    @Test
    @DisplayName("should let a consumer read the customer when it receives CUSTOMER_CREATED")
    void publishAfterCommit_customerIsReadable() throws Exception {
        ExecutorService consumer = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> consumerRead = consumer.submit(readCustomerOnReceipt());

            createCustomer("Bruno Lima", "39053344705", "bruno@example.com");

            MvcResult read = consumerRead.get(CONSUMER_WAIT_MS * 2, TimeUnit.MILLISECONDS);
            assertThat(read.getResponse().getStatus()).isEqualTo(200);
            assertThat(JSON.readTree(read.getResponse().getContentAsString()).path("cpf").asString())
                    .isEqualTo("39053344705");
        } finally {
            consumer.shutdownNow();
        }
    }

    // AC-4 (ticket 06) / NFR-10
    // Given RabbitMQ is down
    // When an ADMIN POSTs /customers {"name":"Carla Dias","cpf":"93541134780","email":"carla@example.com"}
    // Then the response is 201 with id X
    //  And outbox_events has exactly 1 row with event_type "CUSTOMER_CREATED", aggregate_id X, sent_at NULL,
    //      and payload.eventId == event_id (a valid UUID)
    @Test
    @DisplayName("should return 201 and store one pending outbox row when RabbitMQ is down")
    void rabbitDown_returns201AndWritesOutbox() throws Exception {
        rabbitOutage.stop();

        long customerId = createCustomer("Carla Dias", "93541134780", "carla@example.com");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT event_id, event_type, aggregate_id,"
                + " sent_at, payload->>'eventId' AS payload_event_id FROM outbox_events");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row.get("event_type")).isEqualTo("CUSTOMER_CREATED");
        assertThat(row.get("aggregate_id")).isEqualTo(customerId);
        assertThat(row.get("sent_at")).isNull();
        assertThat(row.get("event_id")).isInstanceOf(UUID.class);
        assertThat(row.get("payload_event_id")).isEqualTo(row.get("event_id").toString());
    }

    // AC-4 (ticket 06)
    // Given RabbitMQ is up but the publish is nacked / the confirm times out / the message is returned as unroutable
    //       (e.g. exchange missing or confirm timeout set very low)
    // When an ADMIN creates customer cpf 98765432100
    // Then the response is 201
    //  And outbox_events has exactly 1 row for that customer with sent_at NULL
    // The confirm timeout itself is covered by ConfirmedPublisherTest; here the broker really returns and nacks.
    @Test
    @DisplayName("should store a pending outbox row when the publish is nacked, times out or is returned")
    void nackOrTimeoutOrReturn_writesOutbox() throws Exception {
        // Broker-side failure -> the last_error_type it must be stored with (updated AC-6).
        Map<Runnable, String> brokerFailures = new LinkedHashMap<>();
        brokerFailures.put(() -> amqpAdmin.removeBinding(customerCreatedBinding), "UNROUTABLE");
        brokerFailures.put(() -> amqpAdmin.deleteExchange(MessagingTopology.CUSTOMER_EVENTS_EXCHANGE), "NACK");

        for (Map.Entry<Runnable, String> brokerFailure : brokerFailures.entrySet()) {
            cleanState();
            brokerFailure.getKey().run();

            long customerId = createCustomer("Diego Alves", "98765432100", "diego@example.com");

            assertThat(jdbcTemplate.queryForList("SELECT sent_at, attempts, last_error_type, last_failed_at"
                    + " FROM outbox_events WHERE aggregate_id = ?", customerId))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.get("sent_at")).isNull();
                        assertThat(row.get("attempts")).isEqualTo(0);
                        assertThat(row.get("last_error_type")).isEqualTo(brokerFailure.getValue());
                        assertThat(row.get("last_failed_at")).isNotNull();
                    });
            assertThat(outboxRowCount()).isEqualTo(1);
            amqpAdmin.initialize();
        }
    }

    // AC-5 (ticket 06) / NFR-7
    // Given RabbitMQ was down, 1 pending outbox row exists with event_id E for customer X
    // When RabbitMQ comes back and the relay runs
    // Then within 30 s one message with eventId E (same UUID as the row) and customerId X arrives on "customer.created"
    //  And the outbox row has sent_at NOT NULL
    @Test
    @DisplayName("should republish pending outbox rows with their original eventId when RabbitMQ recovers")
    void relayPublishesPendingRowsAfterRecovery() throws Exception {
        rabbitOutage.stop();
        long customerId = createCustomer("Ana Souza", "52998224725", "ana@example.com");
        UUID eventId = jdbcTemplate.queryForObject("SELECT event_id FROM outbox_events", UUID.class);

        rabbitOutage.start();
        Awaitility.await().atMost(RECOVERY_DEADLINE).pollInterval(RELAY_RETRY_POLL).untilAsserted(() -> {
            outboxRelay.relayPending();
            assertThat(sentAtOf(eventId)).isNotNull();
        });

        Message message = rabbitTemplate.receive(QUEUE, PUBLISH_WINDOW_MS);
        assertThat(message).isNotNull();
        JsonNode body = bodyOf(message);
        assertThat(body.path("eventId").asString()).isEqualTo(eventId.toString());
        assertThat(body.path("customerId").asLong()).isEqualTo(customerId);
        assertThat(rabbitTemplate.receive(QUEUE)).as("no second message").isNull();
    }

    // AC-5 (ticket 06)
    // Given 3 pending outbox rows created at t1 < t2 < t3 (customers A, B, C)
    // When RabbitMQ is up and the relay runs
    // Then messages arrive on "customer.created" in order A, B, C (ordered by created_at)
    @Test
    @DisplayName("should relay pending rows in created_at order")
    void relayOrdersByCreatedAt() {
        long customerA = insertCustomer("Cliente A", "52998224725");
        long customerB = insertCustomer("Cliente B", "11144477735");
        long customerC = insertCustomer("Cliente C", "39053344705");
        Instant t1 = Instant.now().minusSeconds(30);
        // Inserted out of order on purpose: only created_at may decide the sending order.
        insertPendingRow(customerC, t1.plusSeconds(2));
        insertPendingRow(customerA, t1);
        insertPendingRow(customerB, t1.plusSeconds(1));

        outboxRelay.relayPending();

        assertThat(receiveAll()).extracting(message -> bodyOf(message).path("customerId").asLong())
                .containsExactly(customerA, customerB, customerC);
    }

    // AC-6 (ticket 06)
    // Given 1 pending outbox row with attempts 0, last_error NULL, next_attempt_at N0, and RabbitMQ still down
    // When the relay runs once
    // Then the row has attempts 1, last_error NOT NULL (non-empty), next_attempt_at > N0, sent_at NULL
    // When the relay runs a second time after next_attempt_at
    // Then attempts == 2 and the gap (next_attempt_at - now) is greater than after the first failure (backoff grows)
    // Updated AC-6: after each failure last_error_type is "BROKER_DOWN" and last_failed_at is set (and moves forward)
    @Test
    @DisplayName("should increment attempts, set last_error and push next_attempt_at when a relay publish fails")
    void relayFailure_updatesAttemptsAndBackoff() {
        long customerId = insertCustomer("Ana Souza", "52998224725");
        Instant firstDue = Instant.now().minusSeconds(1);
        UUID eventId = insertPendingRow(customerId, firstDue);
        rabbitOutage.stop();

        outboxRelay.relayPending();
        Duration firstGap = Duration.between(Instant.now(), nextAttemptAtOf(eventId));

        Map<String, Object> afterFirst = outboxRow(eventId);
        assertThat(afterFirst.get("attempts")).isEqualTo(1);
        assertThat((String) afterFirst.get("last_error")).isNotBlank();
        assertThat(afterFirst.get("last_error_type")).isEqualTo("BROKER_DOWN");
        assertThat(afterFirst.get("last_failed_at")).isNotNull();
        assertThat(nextAttemptAtOf(eventId)).isAfter(firstDue);
        assertThat(afterFirst.get("sent_at")).isNull();

        Instant secondDue = nextAttemptAtOf(eventId);
        Awaitility.await().atMost(RECOVERY_DEADLINE).until(() -> Instant.now().isAfter(secondDue));
        outboxRelay.relayPending();
        Duration secondGap = Duration.between(Instant.now(), nextAttemptAtOf(eventId));

        Map<String, Object> afterSecond = outboxRow(eventId);
        assertThat(afterSecond.get("attempts")).isEqualTo(2);
        assertThat(afterSecond.get("last_error_type")).isEqualTo("BROKER_DOWN");
        assertThat(((Timestamp) afterSecond.get("last_failed_at")).toInstant())
                .isAfter(((Timestamp) afterFirst.get("last_failed_at")).toInstant());
        assertThat(afterSecond.get("sent_at")).isNull();
        assertThat(secondGap).isGreaterThan(firstGap);
    }

    // AC-7 (ticket 06)
    // Given 10 pending outbox rows and RabbitMQ up
    // When 2 relay executions run concurrently (two threads calling the relay method at the same time)
    // Then exactly 10 messages arrive on "customer.created" (each eventId once)
    //  And all 10 rows have sent_at NOT NULL
    @Test
    @DisplayName("should send each outbox row once when two relay runs overlap")
    void overlappingRelays_doNotDoubleSend() throws Exception {
        Set<String> eventIds = new HashSet<>();
        Instant createdAt = Instant.now().minusSeconds(60);
        for (int row = 0; row < CONCURRENT_ROWS; row++) {
            eventIds.add(insertPendingRow(row + 1, createdAt.plusMillis(row)).toString());
        }

        runConcurrently(outboxRelay::relayPending, outboxRelay::relayPending);

        List<String> receivedEventIds = receiveAll().stream()
                .map(message -> bodyOf(message).path("eventId").asString())
                .toList();
        assertThat(receivedEventIds).hasSize(CONCURRENT_ROWS).doesNotHaveDuplicates();
        assertThat(new HashSet<>(receivedEventIds)).isEqualTo(eventIds);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events WHERE sent_at IS NOT NULL",
                Long.class)).isEqualTo(CONCURRENT_ROWS);
    }

    // AC-8 (ticket 06)
    // Given a fresh RabbitMQ with no topology
    // When the app starts
    // Then exchange "customer.events" exists, type topic, durable
    //  And queue "customer.created" exists, durable, bound to "customer.events" with routing key "customer.created"
    // The container is created with this test context and nothing declares the topology except the app.
    @Test
    @DisplayName("should declare the durable customer.events exchange and customer.created queue at startup")
    void topologyDeclaredAtStartup() throws Exception {
        assertThat(RabbitCtl.list(rabbitContainer, "list_exchanges", "name", "type", "durable"))
                .anySatisfy(exchange -> {
                    assertThat(exchange.path("name").asString()).isEqualTo("customer.events");
                    assertThat(exchange.path("type").asString()).isEqualTo("topic");
                    assertThat(exchange.path("durable").asBoolean()).isTrue();
                });
        assertThat(RabbitCtl.list(rabbitContainer, "list_queues", "name", "durable"))
                .anySatisfy(queue -> {
                    assertThat(queue.path("name").asString()).isEqualTo("customer.created");
                    assertThat(queue.path("durable").asBoolean()).isTrue();
                });
        assertThat(RabbitCtl.list(rabbitContainer, "list_bindings", "source_name", "destination_name", "routing_key"))
                .anySatisfy(binding -> {
                    assertThat(binding.path("source_name").asString()).isEqualTo("customer.events");
                    assertThat(binding.path("destination_name").asString()).isEqualTo("customer.created");
                    assertThat(binding.path("routing_key").asString()).isEqualTo("customer.created");
                });
    }

    /**
     * Forces the creation transaction to fail after the insert and after the event is raised, just before commit.
     * The configuration class is itself the bean that listens.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class CommitFailureInjection {

        private volatile boolean armed;

        void arm() {
            armed = true;
        }

        void disarm() {
            armed = false;
        }

        @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
        void failBeforeCommit(CustomerCreatedEvent event) {
            if (armed) {
                throw new IllegalStateException("Injected failure before commit of customer " + event.customerId());
            }
        }
    }

    private Callable<MvcResult> readCustomerOnReceipt() {
        return () -> {
            Message message = rabbitTemplate.receive(QUEUE, CONSUMER_WAIT_MS);
            assertThat(message).as("CUSTOMER_CREATED message").isNotNull();
            long customerId = bodyOf(message).path("customerId").asLong();
            return mockMvc.perform(get("/customers/" + customerId)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))).andReturn();
        };
    }

    private long createCustomer(String name, String cpf, String email) throws Exception {
        String body = postCustomer(name, cpf, email)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).path("id").asLong();
    }

    private ResultActions postCustomer(String name, String cpf, String email) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"cpf\":\"" + cpf + "\",\"email\":\"" + email + "\"}";
        return mockMvc.perform(post("/customers")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // Straight into the table, so no CUSTOMER_CREATED event is raised.
    private long insertCustomer(String name, String cpf) {
        return jdbcTemplate.queryForObject(INSERT_CUSTOMER, Long.class, name, name.toLowerCase(), cpf);
    }

    private UUID insertPendingRow(long customerId, Instant createdAt) {
        UUID eventId = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"CUSTOMER_CREATED\",\"customerId\":"
                + customerId + ",\"createdAt\":\"" + createdAt + "\"}";
        jdbcTemplate.update(INSERT_OUTBOX_ROW, eventId, customerId, payload, Timestamp.from(createdAt),
                Timestamp.from(createdAt));
        return eventId;
    }

    private long outboxRowCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events", Long.class);
    }

    private Map<String, Object> outboxRow(UUID eventId) {
        return jdbcTemplate.queryForMap("SELECT attempts, last_error, last_error_type, last_failed_at, sent_at"
                + " FROM outbox_events WHERE event_id = ?",
                eventId);
    }

    private Instant nextAttemptAtOf(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT next_attempt_at FROM outbox_events WHERE event_id = ?",
                Timestamp.class, eventId).toInstant();
    }

    private Timestamp sentAtOf(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT sent_at FROM outbox_events WHERE event_id = ?", Timestamp.class,
                eventId);
    }

    private List<Message> receiveAll() {
        List<Message> messages = new ArrayList<>();
        Message message = rabbitTemplate.receive(QUEUE, PUBLISH_WINDOW_MS);
        while (message != null) {
            messages.add(message);
            message = rabbitTemplate.receive(QUEUE, PUBLISH_WINDOW_MS);
        }
        return messages;
    }

    private static void runConcurrently(Runnable... tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
        try {
            CountDownLatch startTogether = new CountDownLatch(1);
            List<Future<?>> runs = new ArrayList<>();
            for (Runnable task : tasks) {
                runs.add(executor.submit(() -> {
                    startTogether.await();
                    task.run();
                    return null;
                }));
            }
            startTogether.countDown();
            for (Future<?> run : runs) {
                run.get(RECOVERY_DEADLINE.toSeconds(), TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static JsonNode bodyOf(Message message) {
        return JSON.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
    }
}
