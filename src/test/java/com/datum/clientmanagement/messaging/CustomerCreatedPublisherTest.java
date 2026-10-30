package com.datum.clientmanagement.messaging;

import com.datum.clientmanagement.event.CustomerCreatedEvent;
import com.datum.clientmanagement.exception.PublishFailedException;
import com.datum.clientmanagement.model.entity.OutboxEventEntity;
import com.datum.clientmanagement.model.enums.PublishFailureReason;
import com.datum.clientmanagement.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CustomerCreatedPublisherTest {

	private static final long CUSTOMER_ID = 42L;
	private static final Instant CREATED_AT = Instant.parse("2026-10-28T10:15:30.123456Z");
	private static final Instant NOW = Instant.parse("2026-10-28T10:15:31Z");
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final ConfirmedPublisher confirmedPublisher = mock(ConfirmedPublisher.class);
	private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
	private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
	private final CustomerCreatedPublisher publisher = new CustomerCreatedPublisher(confirmedPublisher, outbox, JSON,
			Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);

	@Test
	void onCustomerCreated_publishesTheCustomerCreatedPayload() {
		publisher.onCustomerCreated(new CustomerCreatedEvent(CUSTOMER_ID, CREATED_AT));

		ArgumentCaptor<UUID> eventId = ArgumentCaptor.forClass(UUID.class);
		ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
		verify(confirmedPublisher).publish(eventId.capture(), payload.capture());
		JsonNode body = JSON.readTree(payload.getValue());
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("eventId", "eventType", "customerId", "createdAt");
		assertThat(body.path("eventId").asString()).isEqualTo(eventId.getValue().toString());
		assertThat(body.path("eventType").asString()).isEqualTo("CUSTOMER_CREATED");
		assertThat(body.path("customerId").asLong()).isEqualTo(CUSTOMER_ID);
		assertThat(body.path("createdAt").asString()).isEqualTo("2026-10-28T10:15:30.123456Z");
		verifyNoInteractions(outbox);
	}

	@Test
	void onCustomerCreated_payloadIsExactlyTheDocumentedJsonInFieldOrder() {
		publisher.onCustomerCreated(new CustomerCreatedEvent(CUSTOMER_ID, CREATED_AT));

		ArgumentCaptor<UUID> eventId = ArgumentCaptor.forClass(UUID.class);
		ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
		verify(confirmedPublisher).publish(eventId.capture(), payload.capture());
		assertThat(payload.getValue()).isEqualTo("{\"eventId\":\"" + eventId.getValue()
				+ "\",\"eventType\":\"CUSTOMER_CREATED\",\"customerId\":42,"
				+ "\"createdAt\":\"2026-10-28T10:15:30.123456Z\"}");
	}

	@Test
	void onCustomerCreated_publishFails_storesTheSamePayloadAndTheFailureInTheOutboxInANewTransaction() {
		when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
		doThrow(new PublishFailedException(PublishFailureReason.BROKER_DOWN, "Connection refused"))
				.when(confirmedPublisher).publish(any(), anyString());

		publisher.onCustomerCreated(new CustomerCreatedEvent(CUSTOMER_ID, CREATED_AT));

		ArgumentCaptor<UUID> publishedId = ArgumentCaptor.forClass(UUID.class);
		ArgumentCaptor<String> publishedPayload = ArgumentCaptor.forClass(String.class);
		verify(confirmedPublisher).publish(publishedId.capture(), publishedPayload.capture());
		ArgumentCaptor<OutboxEventEntity> stored = ArgumentCaptor.forClass(OutboxEventEntity.class);
		verify(outbox).save(stored.capture());
		OutboxEventEntity event = stored.getValue();
		assertThat(event.getEventId()).isEqualTo(publishedId.getValue());
		assertThat(event.getPayload()).isEqualTo(publishedPayload.getValue());
		assertThat(event.getAttempts()).as("attempts counts relay failures only").isZero();
		assertThat(event.getLastError()).isEqualTo("Connection refused");
		assertThat(event.getLastErrorType()).isEqualTo(PublishFailureReason.BROKER_DOWN);
		assertThat(event.getLastFailedAt()).isEqualTo(NOW);
		assertThat(event.getNextAttemptAt()).as("due for the relay straight away").isEqualTo(NOW);
		assertThat(event.getSentAt()).isNull();
		ArgumentCaptor<TransactionDefinition> transaction = ArgumentCaptor.forClass(TransactionDefinition.class);
		verify(transactionManager).getTransaction(transaction.capture());
		assertThat(transaction.getValue().getPropagationBehavior())
				.isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		verify(transactionManager).commit(any());
	}

	@Test
	void onCustomerCreated_outboxWriteFails_doesNotPropagate() {
		when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
		doThrow(new PublishFailedException(PublishFailureReason.BROKER_DOWN, "down"))
				.when(confirmedPublisher).publish(any(), anyString());
		doThrow(new DataAccessResourceFailureException("db down")).when(outbox).save(any(OutboxEventEntity.class));

		assertThatCode(() -> publisher.onCustomerCreated(new CustomerCreatedEvent(CUSTOMER_ID, CREATED_AT)))
				.doesNotThrowAnyException();
	}
}
