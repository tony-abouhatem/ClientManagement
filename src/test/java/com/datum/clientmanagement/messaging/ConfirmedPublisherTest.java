package com.datum.clientmanagement.messaging;

import com.datum.clientmanagement.config.MessagingProperties;
import com.datum.clientmanagement.config.MessagingTopology;
import com.datum.clientmanagement.exception.PublishFailedException;
import com.datum.clientmanagement.model.PublishFailureReason;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** RabbitTemplate is the network boundary: it is mocked and completes the confirm the way the broker would. */
class ConfirmedPublisherTest {

	private static final UUID EVENT_ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
	private static final String PAYLOAD = "{\"eventId\":\"0f8fad5b-d9cb-469f-a165-70867728950e\"}";
	private static final Duration CONFIRM_TIMEOUT = Duration.ofMillis(100);

	private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
	private final ConfirmedPublisher publisher =
			new ConfirmedPublisher(rabbitTemplate, new MessagingProperties(CONFIRM_TIMEOUT));

	@Test
	void publish_ackedAndRouted_sendsPersistentJsonToCustomerCreated() {
		brokerAnswers(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null)));

		assertThatCode(() -> publisher.publish(EVENT_ID, PAYLOAD)).doesNotThrowAnyException();

		ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
		ArgumentCaptor<CorrelationData> correlation = ArgumentCaptor.forClass(CorrelationData.class);
		verify(rabbitTemplate).send(eq(MessagingTopology.CUSTOMER_EVENTS_EXCHANGE),
				eq(MessagingTopology.CUSTOMER_CREATED_ROUTING_KEY), sent.capture(), correlation.capture());
		MessageProperties properties = sent.getValue().getMessageProperties();
		assertThat(new String(sent.getValue().getBody(), StandardCharsets.UTF_8)).isEqualTo(PAYLOAD);
		assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
		assertThat(properties.getMessageId()).isEqualTo(EVENT_ID.toString());
		assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
		assertThat(correlation.getValue().getId()).isEqualTo(EVENT_ID.toString());
	}

	@Test
	void publish_nacked_fails() {
		brokerAnswers(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "nack")));

		PublishFailedException failure = publishFailure();
		assertThat(failure.getReason()).isEqualTo(PublishFailureReason.NACK);
		assertThat(failure).hasMessageContaining("nacked");
	}

	@Test
	void publish_returnedAsUnroutable_failsEvenThoughAcked() {
		brokerAnswers(correlation -> {
			correlation.setReturned(new ReturnedMessage(new Message(new byte[0]), 312, "NO_ROUTE",
					MessagingTopology.CUSTOMER_EVENTS_EXCHANGE, MessagingTopology.CUSTOMER_CREATED_ROUTING_KEY));
			correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
		});

		PublishFailedException failure = publishFailure();
		assertThat(failure.getReason()).isEqualTo(PublishFailureReason.UNROUTABLE);
		assertThat(failure).hasMessageContaining("NO_ROUTE");
	}

	@Test
	void publish_noConfirmWithinTimeout_fails() {
		brokerAnswers(correlation -> { });

		PublishFailedException failure = publishFailure();
		assertThat(failure.getReason()).isEqualTo(PublishFailureReason.CONFIRM_TIMEOUT);
		assertThat(failure).hasMessageContaining("No publisher confirm");
	}

	@Test
	void publish_brokerUnreachable_fails() {
		doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
				.when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

		PublishFailedException failure = publishFailure();
		assertThat(failure.getReason()).isEqualTo(PublishFailureReason.BROKER_DOWN);
		assertThat(failure).hasCauseInstanceOf(AmqpConnectException.class);
	}

	@Test
	void publish_confirmCompletedExceptionally_fails() {
		brokerAnswers(correlation -> correlation.getFuture().completeExceptionally(new IllegalStateException("boom")));

		assertThat(publishFailure().getReason()).isEqualTo(PublishFailureReason.CONFIRM_TIMEOUT);
	}

	@Test
	void publish_interruptedWhileWaiting_failsAndKeepsTheInterruptFlag() {
		brokerAnswers(correlation -> Thread.currentThread().interrupt());

		try {
			assertThat(publishFailure().getReason()).isEqualTo(PublishFailureReason.CONFIRM_TIMEOUT);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		} finally {
			Thread.interrupted();
		}
	}

	private PublishFailedException publishFailure() {
		PublishFailedException failure = catchThrowableOfType(PublishFailedException.class,
				() -> publisher.publish(EVENT_ID, PAYLOAD));
		assertThat(failure).as("publish should fail").isNotNull();
		return failure;
	}

	private void brokerAnswers(Consumer<CorrelationData> broker) {
		doAnswer(invocation -> {
			broker.accept(invocation.getArgument(3));
			return null;
		}).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
	}
}
