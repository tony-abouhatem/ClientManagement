package com.datum.clientmanagement.messaging;

import com.datum.clientmanagement.TestcontainersConfiguration;
import com.datum.clientmanagement.config.MessagingTopology;
import com.datum.clientmanagement.exception.PublishFailedException;
import com.datum.clientmanagement.model.enums.PublishFailureReason;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Ticket 06 AC-6 give-up rules against real PostgreSQL, with max-attempts lowered to {@value #MAX_ATTEMPTS}: a dead
 * row (attempts == max-attempts) is never picked up, reaching the limit logs one ERROR, and resetting attempts
 * replays the row.
 */
@SpringBootTest(properties = {"app.outbox.relay-interval=1h",
		"resilience4j.retry.instances.outbox.max-attempts=" + OutboxDeadRowTest.MAX_ATTEMPTS})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"}) // IDE has no database; the SQL runs against Testcontainers Postgres
class OutboxDeadRowTest {

	static final int MAX_ATTEMPTS = 2;

	private static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofSeconds(5);

	private static final String INSERT_OUTBOX_ROW = "INSERT INTO outbox_events"
			+ " (event_id, event_type, aggregate_id, payload, created_at, attempts, next_attempt_at)"
			+ " VALUES (?, 'CUSTOMER_CREATED', 1, CAST(? AS jsonb), ?, ?, ?)";

	@Autowired
	private OutboxRelay relay;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private AmqpAdmin amqpAdmin;

	@MockitoSpyBean
	private ConfirmedPublisher publisher;

	@BeforeEach
	void cleanState() {
		jdbcTemplate.update("DELETE FROM outbox_events");
		amqpAdmin.purgeQueue(MessagingTopology.CUSTOMER_CREATED_QUEUE, false);
	}

	@Test
	void relayPending_deadRow_isNotPickedUp() {
		UUID dead = insertDueRow(MAX_ATTEMPTS);

		relay.relayPending();

		verify(publisher, never()).publish(any(UUID.class), anyString());
		assertThat(row(dead)).containsEntry("attempts", MAX_ATTEMPTS).containsEntry("sent_at", null);
	}

	@Test
	void relayPending_failureReachingMaxAttempts_logsErrorAndTheRowIsNotRetried(CapturedOutput output) {
		UUID eventId = insertDueRow(MAX_ATTEMPTS - 1);
		doThrow(new PublishFailedException(PublishFailureReason.NACK, "RabbitMQ nacked the message"))
				.when(publisher).publish(any(UUID.class), anyString());

		relay.relayPending();

		Map<String, Object> afterLimit = row(eventId);
		assertThat(afterLimit).containsEntry("attempts", MAX_ATTEMPTS).containsEntry("last_error_type", "NACK");
		assertThat(afterLimit.get("last_failed_at")).isNotNull();
		assertThat(output.getOut().lines()
				.filter(line -> line.contains(" ERROR ") && line.contains(eventId.toString())).toList())
				.singleElement()
				.satisfies(line -> assertThat(line).contains(MAX_ATTEMPTS + " attempts", "NACK"));

		jdbcTemplate.update("UPDATE outbox_events SET next_attempt_at = ? WHERE event_id = ?",
				Timestamp.from(Instant.now().minusSeconds(1)), eventId);
		relay.relayPending();

		verify(publisher, times(1)).publish(any(UUID.class), anyString());
	}

	@Test
	void relayPending_afterManualReset_relaysTheDeadRowAgain() {
		UUID eventId = insertDueRow(MAX_ATTEMPTS);
		relay.relayPending();
		assertThat(row(eventId).get("sent_at")).isNull();

		// The documented manual replay. It uses the database clock, which may run slightly ahead of the JVM's.
		jdbcTemplate.update("UPDATE outbox_events SET attempts = 0, next_attempt_at = now() WHERE event_id = ?",
				eventId);

		Awaitility.await().atMost(CLOCK_SKEW_TOLERANCE).untilAsserted(() -> {
			relay.relayPending();
			assertThat(row(eventId).get("sent_at")).isNotNull();
		});
	}

	private UUID insertDueRow(int attempts) {
		UUID eventId = UUID.randomUUID();
		Timestamp past = Timestamp.from(Instant.now().minusSeconds(10));
		jdbcTemplate.update(INSERT_OUTBOX_ROW, eventId, "{\"eventId\":\"" + eventId + "\",\"customerId\":1}", past,
				attempts, past);
		return eventId;
	}

	private Map<String, Object> row(UUID eventId) {
		return jdbcTemplate.queryForMap("SELECT attempts, last_error_type, last_failed_at, sent_at FROM outbox_events"
				+ " WHERE event_id = ?", eventId);
	}
}
