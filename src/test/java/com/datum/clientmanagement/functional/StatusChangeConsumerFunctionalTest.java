/*
 * Ticket: 07 (GitHub issue #8)
 *
 * Interfaces under test:
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

import com.datum.clientmanagement.RabbitCtl;
import com.datum.clientmanagement.StatusChangeFixture;
import com.datum.clientmanagement.TestcontainersConfiguration;
import com.datum.clientmanagement.config.MessagingTopology;
import com.datum.clientmanagement.messaging.StatusChangeMessageParser;
import com.datum.clientmanagement.service.StatusChangeProcessor;
import com.rabbitmq.client.AMQP;
import org.awaitility.Awaitility;
import org.awaitility.core.ThrowingRunnable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.datum.clientmanagement.StatusChangeFixture.statusChange;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Two consumers with one unacked message each, so two deliveries can really be processed at the same time.
// A 100 ms first retry backoff (production: 1 s) keeps the retry tests fast; the attempt count is unchanged.
@SpringBootTest(properties = {
		"spring.rabbitmq.listener.simple.concurrency=2",
		"spring.rabbitmq.listener.simple.prefetch=1",
		"spring.rabbitmq.listener.simple.retry.initial-interval=100ms"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"}) // IDE has no database; the SQL runs against Testcontainers Postgres
class StatusChangeConsumerFunctionalTest {

	private static final String QUEUE = MessagingTopology.STATUS_CHANGE_QUEUE;
	private static final String DLQ = MessagingTopology.STATUS_CHANGE_DEAD_LETTER_QUEUE;
	private static final String CPF = "52998224725";
	private static final long UNKNOWN_CUSTOMER_ID = 999_999L;
	private static final Duration EVENTUALLY = Duration.ofSeconds(5);
	// Upper bound for a transient failure to use up its 3 attempts (100 ms + 200 ms backoff) and be dead-lettered.
	private static final Duration RETRIES_EXHAUSTED = Duration.ofSeconds(15);
	private static final long VERIFY_TIMEOUT_MS = 5_000;
	private static final long CONCURRENT_ARRIVAL_SECONDS = 5;
	private static final String INSERT_CUSTOMER = "INSERT INTO customers"
			+ " (name, name_normalized, cpf, email, status, created_at, updated_at)"
			+ " VALUES ('Ana Souza', 'ana souza', ?, 'ana@example.com', ?, now(), now()) RETURNING id";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private RabbitTemplate rabbitTemplate;

	@Autowired
	private AmqpAdmin amqpAdmin;

	@Autowired
	private RabbitMQContainer rabbitContainer;

	// Each listener invocation (first delivery or retry) parses the message first, so this counts attempts.
	@MockitoSpyBean
	private StatusChangeMessageParser parser;

	@MockitoSpyBean
	private StatusChangeProcessor processor;

	private StatusChangeFixture fixture;

	@BeforeEach
	void cleanState() {
		fixture = new StatusChangeFixture(jdbcTemplate, rabbitTemplate);
		jdbcTemplate.update("DELETE FROM processed_events");
		jdbcTemplate.update("DELETE FROM customers");
		amqpAdmin.purgeQueue(QUEUE, false);
		amqpAdmin.purgeQueue(DLQ, false);
	}

	// AC-1 (ticket 07)
	// Given customer X (cpf 52998224725) with status ACTIVE
	// When {"eventId":"3f1c2a9e-6b1d-4f6e-9a0b-1c2d3e4f5a60","eventType":"CUSTOMER_STATUS_CHANGE","customerId":X,"status":"INACTIVE"}
	//      is published
	// Then eventually GET /customers/X returns status "INACTIVE"
	//  And processed_events has exactly 1 row with event_id 3f1c2a9e-6b1d-4f6e-9a0b-1c2d3e4f5a60 and event_type "CUSTOMER_STATUS_CHANGE"
	//  And "customer.status-change.dlq" has 0 messages
	@Test
	@DisplayName("should set the customer INACTIVE and record the eventId when a valid status change is consumed")
	void validStatusChange_isApplied() {
		long customerId = insertCustomer("ACTIVE");
		UUID eventId = UUID.fromString("3f1c2a9e-6b1d-4f6e-9a0b-1c2d3e4f5a60");

		fixture.publish(statusChange(eventId, customerId, "INACTIVE"));

		eventually(() -> mockMvc.perform(get("/customers/" + customerId)
						.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("INACTIVE")));
		assertThat(jdbcTemplate.queryForList("SELECT event_id, event_type FROM processed_events"))
				.singleElement()
				.satisfies(row -> {
					assertThat(row.get("event_id")).isEqualTo(eventId);
					assertThat(row.get("event_type")).isEqualTo("CUSTOMER_STATUS_CHANGE");
				});
		assertThat(fixture.deadLetterCount()).isZero();
	}

	// AC-2 (ticket 07) / NFR-8
	// Given customer X ACTIVE
	// When the same message (eventId "7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d", status INACTIVE) is published 3 times
	// Then eventually customer X is INACTIVE
	//  And processed_events has exactly 1 row for that eventId
	//  And queue "customer.status-change" is empty (all 3 acked) and the DLQ has 0 messages
	//  And the customer's version advanced by exactly 1
	@Test
	@DisplayName("should apply the change once and ack every delivery when the same message arrives 3 times")
	void duplicateDeliveries_appliedOnce() {
		long customerId = insertCustomer("ACTIVE");
		UUID eventId = UUID.fromString("7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d");
		String message = statusChange(eventId, customerId, "INACTIVE");

		fixture.publish(message);
		fixture.publish(message);
		fixture.publish(message);

		verify(processor, timeout(VERIFY_TIMEOUT_MS).times(3)).process(any());
		eventually(() -> assertThat(RabbitCtl.messageCount(rabbitContainer, QUEUE)).isZero());
		assertThat(fixture.statusOf(customerId)).isEqualTo("INACTIVE");
		assertThat(fixture.processedRows(eventId)).isEqualTo(1);
		assertThat(fixture.deadLetterCount()).isZero();
		assertThat(fixture.versionOf(customerId)).isEqualTo(1);
	}

	// AC-3 (ticket 07)
	// Given customer X ACTIVE
	// When message E1 (eventId "11111111-1111-4111-8111-111111111111", status INACTIVE) is consumed
	//  And then message E2 (eventId "22222222-2222-4222-8222-222222222222", status ACTIVE) is consumed
	//  And then E1 is delivered again
	// Then customer X ends with status ACTIVE (E1 replay does not revert it)
	//  And processed_events has exactly 2 rows (E1, E2)
	@Test
	@DisplayName("should not revert the status when an old eventId is redelivered after a newer change")
	void replayedOldEvent_doesNotRevert() {
		long customerId = insertCustomer("ACTIVE");
		UUID firstId = UUID.fromString("11111111-1111-4111-8111-111111111111");
		UUID secondId = UUID.fromString("22222222-2222-4222-8222-222222222222");
		String first = statusChange(firstId, customerId, "INACTIVE");
		String second = statusChange(secondId, customerId, "ACTIVE");

		fixture.publish(first);
		eventually(() -> assertThat(fixture.statusOf(customerId)).isEqualTo("INACTIVE"));
		fixture.publish(second);
		eventually(() -> assertThat(fixture.statusOf(customerId)).isEqualTo("ACTIVE"));
		fixture.publish(first);

		verify(processor, timeout(VERIFY_TIMEOUT_MS).times(3)).process(any());
		eventually(() -> assertThat(RabbitCtl.messageCount(rabbitContainer, QUEUE)).isZero());
		assertThat(fixture.statusOf(customerId)).isEqualTo("ACTIVE");
		assertThat(jdbcTemplate.queryForList("SELECT event_id FROM processed_events", UUID.class))
				.containsExactlyInAnyOrder(firstId, secondId);
	}

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
	@DisplayName("should send the message straight to the DLQ when it is malformed, incomplete, has an invalid status or wrong eventType")
	void invalidMessage_goesToDlqWithoutRetry() {
		long customerId = insertCustomer("ACTIVE");
		List<String> invalidMessages = List.of(
				"{not json",
				"{\"eventType\":\"CUSTOMER_STATUS_CHANGE\",\"customerId\":" + customerId + ",\"status\":\"INACTIVE\"}",
				"{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"CUSTOMER_STATUS_CHANGE\",\"status\":\"INACTIVE\"}",
				"{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"CUSTOMER_STATUS_CHANGE\",\"customerId\":"
						+ customerId + "}",
				statusChange(UUID.randomUUID(), customerId, "BLOCKED"),
				"{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"CUSTOMER_CREATED\",\"customerId\":"
						+ customerId + ",\"status\":\"INACTIVE\"}");

		invalidMessages.forEach(fixture::publish);

		// A retry would parse a message again before dead-lettering it, so one parse per message proves none.
		eventually(() -> assertThat(fixture.deadLetterCount()).isEqualTo(invalidMessages.size()));
		verify(parser, times(invalidMessages.size())).parse(any());
		assertThat(drain(DLQ)).containsExactlyInAnyOrderElementsOf(invalidMessages);
		verify(processor, never()).process(any());
		assertThat(fixture.statusOf(customerId)).isEqualTo("ACTIVE");
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM processed_events", Long.class)).isZero();
	}

	// AC-5 (ticket 07) / NFR-9
	// Given no customer with id 999999
	// When {"eventId":"44444444-4444-4444-8444-444444444444","eventType":"CUSTOMER_STATUS_CHANGE","customerId":999999,"status":"INACTIVE"}
	//      is published
	// Then the message eventually lands on "customer.status-change.dlq" with no retry
	//  And processed_events has no row with that eventId
	@Test
	@DisplayName("should send the message to the DLQ and record nothing when the customerId is unknown")
	void unknownCustomer_goesToDlq() {
		UUID eventId = UUID.fromString("44444444-4444-4444-8444-444444444444");
		String message = statusChange(eventId, UNKNOWN_CUSTOMER_ID, "INACTIVE");

		fixture.publish(message);

		eventually(() -> assertThat(fixture.deadLetterCount()).isEqualTo(1));
		verify(processor, times(1)).process(any());
		assertThat(drain(DLQ)).containsExactly(message);
		assertThat(fixture.processedRows(eventId)).isZero();
	}

	// AC-6 (ticket 07) / NFR-9
	// Given customer X ACTIVE and the processing step throws a transient exception (e.g. DataAccessResourceFailureException)
	//       on EVERY attempt
	// When a valid INACTIVE message (eventId "55555555-5555-4555-8555-555555555555") is published
	// Then the processing step is invoked exactly 3 times in total (first delivery + 2 retries), never a 4th
	//  And the message then lands on "customer.status-change.dlq" (exactly 1 message there)
	//  And customer X is still ACTIVE and processed_events has no row for that eventId
	@Test
	@DisplayName("should try exactly 3 times then send to the DLQ when a transient failure persists")
	void persistentTransientFailure_goesToDlqAfter3Attempts() {
		long customerId = insertCustomer("ACTIVE");
		UUID eventId = UUID.fromString("55555555-5555-4555-8555-555555555555");
		doThrow(new DataAccessResourceFailureException("simulated database outage")).when(processor).process(any());

		fixture.publish(statusChange(eventId, customerId, "INACTIVE"));

		// The message is dead-lettered only after the last attempt, so no further attempt can follow.
		Awaitility.await().atMost(RETRIES_EXHAUSTED)
				.untilAsserted(() -> assertThat(fixture.deadLetterCount()).isEqualTo(1));
		verify(processor, times(3)).process(any());
		assertThat(fixture.statusOf(customerId)).isEqualTo("ACTIVE");
		assertThat(fixture.processedRows(eventId)).isZero();
	}

	// AC-6 (ticket 07)
	// Given customer X ACTIVE and the processing step throws a transient exception on the 1st and 2nd attempts,
	//       and succeeds on the 3rd (last allowed) attempt
	// When a valid INACTIVE message (eventId "66666666-6666-4666-8666-666666666666") is published
	// Then the processing step is invoked exactly 3 times
	//  And customer X eventually becomes INACTIVE, its version advanced by exactly 1
	//  And processed_events has exactly 1 row for that eventId and the DLQ has 0 messages
	@Test
	@DisplayName("should apply the change once when the third and last attempt succeeds after two transient failures")
	void transientFailureRecoversOnThirdAttempt_appliedOnce() {
		long customerId = insertCustomer("ACTIVE");
		UUID eventId = UUID.fromString("66666666-6666-4666-8666-666666666666");
		DataAccessResourceFailureException outage = new DataAccessResourceFailureException("simulated database outage");
		doThrow(outage).doThrow(outage).doCallRealMethod().when(processor).process(any());

		fixture.publish(statusChange(eventId, customerId, "INACTIVE"));

		Awaitility.await().atMost(RETRIES_EXHAUSTED)
				.untilAsserted(() -> assertThat(fixture.statusOf(customerId)).isEqualTo("INACTIVE"));
		eventually(() -> assertThat(RabbitCtl.messageCount(rabbitContainer, QUEUE)).isZero());
		verify(processor, times(3)).process(any());
		assertThat(fixture.versionOf(customerId)).isEqualTo(1);
		assertThat(fixture.processedRows(eventId)).isEqualTo(1);
		assertThat(fixture.deadLetterCount()).isZero();
	}

	// AC-7 (ticket 07)
	// Given customer X already INACTIVE with version V
	// When {"eventId":"77777777-7777-4777-8777-777777777777","eventType":"CUSTOMER_STATUS_CHANGE","customerId":X,"status":"INACTIVE"}
	//      is published
	// Then the message is acked (queue empty, DLQ empty)
	//  And processed_events has 1 row for that eventId
	//  And customer X is still INACTIVE
	@Test
	@DisplayName("should record the event and ack as a no-op when the status already matches")
	void sameStatus_isNoOpButRecorded() {
		long customerId = insertCustomer("INACTIVE");
		long versionBefore = fixture.versionOf(customerId);
		UUID eventId = UUID.fromString("77777777-7777-4777-8777-777777777777");

		fixture.publish(statusChange(eventId, customerId, "INACTIVE"));

		eventually(() -> assertThat(fixture.processedRows(eventId)).isEqualTo(1));
		eventually(() -> assertThat(RabbitCtl.messageCount(rabbitContainer, QUEUE)).isZero());
		assertThat(fixture.deadLetterCount()).isZero();
		assertThat(fixture.statusOf(customerId)).isEqualTo("INACTIVE");
		assertThat(fixture.versionOf(customerId)).isEqualTo(versionBefore);
	}

	// AC-8 (ticket 07)
	// Given a fresh RabbitMQ with no topology
	// When the app starts
	// Then queue "customer.status-change" exists, durable, with argument x-dead-letter-exchange == "customer.dlx"
	//  And it is bound to exchange "customer.events" with routing key "customer.status.change"
	//  And exchange "customer.dlx" exists and queue "customer.status-change.dlq" exists, durable, bound to it
	// The container is created with this test context and nothing declares the topology except the app.
	@Test
	@DisplayName("should declare and bind the status-change queue with DLX and the DLQ at startup")
	void topologyDeclaredAtStartup() {
		assertThat(RabbitCtl.list(rabbitContainer, "list_queues", "name", "durable", "arguments"))
				.anySatisfy(queue -> {
					assertThat(queue.path("name").asString()).isEqualTo("customer.status-change");
					assertThat(queue.path("durable").asBoolean()).isTrue();
					assertThat(queueArgument(queue, "x-dead-letter-exchange")).isEqualTo("customer.dlx");
				})
				.anySatisfy(queue -> {
					assertThat(queue.path("name").asString()).isEqualTo("customer.status-change.dlq");
					assertThat(queue.path("durable").asBoolean()).isTrue();
				});
		assertThat(RabbitCtl.list(rabbitContainer, "list_exchanges", "name", "durable"))
				.anySatisfy(exchange -> {
					assertThat(exchange.path("name").asString()).isEqualTo("customer.dlx");
					assertThat(exchange.path("durable").asBoolean()).isTrue();
				});
		assertThat(RabbitCtl.list(rabbitContainer, "list_bindings", "source_name", "destination_name", "routing_key"))
				.anySatisfy(binding -> {
					assertThat(binding.path("source_name").asString()).isEqualTo("customer.events");
					assertThat(binding.path("destination_name").asString()).isEqualTo("customer.status-change");
					assertThat(binding.path("routing_key").asString()).isEqualTo("customer.status.change");
				})
				.anySatisfy(binding -> {
					assertThat(binding.path("source_name").asString()).isEqualTo("customer.dlx");
					assertThat(binding.path("destination_name").asString()).isEqualTo("customer.status-change.dlq");
				});
	}

	// Edge case (ticket 07)
	// Given customer X ACTIVE
	// When the same message (eventId "88888888-8888-4888-8888-888888888888", status INACTIVE) is delivered twice
	//      and both deliveries are processed concurrently (listener concurrency >= 2)
	// Then processed_events has exactly 1 row for that eventId, customer X is INACTIVE,
	//      both deliveries are acked and the DLQ has 0 messages
	@Test
	@DisplayName("should apply once and ack both when two duplicate deliveries are processed concurrently")
	void concurrentDuplicates_appliedOnce() {
		long customerId = insertCustomer("ACTIVE");
		UUID eventId = UUID.fromString("88888888-8888-4888-8888-888888888888");
		String message = statusChange(eventId, customerId, "INACTIVE");
		// Each delivery waits until the other one has arrived too, then both race into the transaction.
		CountDownLatch bothArrived = new CountDownLatch(2);
		AtomicBoolean ranConcurrently = new AtomicBoolean(true);
		doAnswer(invocation -> {
			bothArrived.countDown();
			if (!bothArrived.await(CONCURRENT_ARRIVAL_SECONDS, TimeUnit.SECONDS))
				ranConcurrently.set(false);
			return invocation.callRealMethod();
		}).when(processor).process(any());

		fixture.publish(message);
		fixture.publish(message);

		verify(processor, timeout(VERIFY_TIMEOUT_MS * 2).times(2)).process(any());
		eventually(() -> assertThat(RabbitCtl.messageCount(rabbitContainer, QUEUE)).isZero());
		assertThat(ranConcurrently).as("both deliveries were in flight at the same time").isTrue();
		assertThat(fixture.processedRows(eventId)).isEqualTo(1);
		assertThat(fixture.statusOf(customerId)).isEqualTo("INACTIVE");
		assertThat(fixture.versionOf(customerId)).isEqualTo(1);
		assertThat(fixture.deadLetterCount()).isZero();
	}

	// Edge case (ticket 07)
	// Given customer X ACTIVE
	// When a valid INACTIVE message (eventId "99999999-9999-4999-8999-999999999999") is published WITHOUT a content_type header
	// Then customer X eventually becomes INACTIVE and the DLQ has 0 messages
	@Test
	@DisplayName("should parse the body as JSON when the content_type header is missing")
	void missingContentType_isParsedAsJson() {
		long customerId = insertCustomer("ACTIVE");
		byte[] body = statusChange(UUID.fromString("99999999-9999-4999-8999-999999999999"), customerId, "INACTIVE")
				.getBytes(StandardCharsets.UTF_8);

		// A raw publish with empty properties: RabbitTemplate would always set a content_type.
		rabbitTemplate.execute(channel -> {
			channel.basicPublish(MessagingTopology.CUSTOMER_EVENTS_EXCHANGE, MessagingTopology.STATUS_CHANGE_ROUTING_KEY,
					new AMQP.BasicProperties(), body);
			return null;
		});

		eventually(() -> assertThat(fixture.statusOf(customerId)).isEqualTo("INACTIVE"));
		assertThat(fixture.deadLetterCount()).isZero();
	}

	private long insertCustomer(String status) {
		return jdbcTemplate.queryForObject(INSERT_CUSTOMER, Long.class, CPF, status);
	}

	private List<String> drain(String queue) {
		List<String> bodies = new ArrayList<>();
		Message message = rabbitTemplate.receive(queue);
		while (message != null) {
			bodies.add(new String(message.getBody(), StandardCharsets.UTF_8));
			message = rabbitTemplate.receive(queue);
		}
		return bodies;
	}

	// rabbitmqctl lists queue arguments as [name, type, value] triples.
	private static String queueArgument(JsonNode queue, String name) {
		for (JsonNode argument : queue.path("arguments")) {
			if (name.equals(argument.path(0).asString()))
				return argument.path(2).asString();
		}
		return null;
	}

	private static void eventually(ThrowingRunnable assertion) {
		Awaitility.await().atMost(EVENTUALLY).untilAsserted(assertion);
	}
}
