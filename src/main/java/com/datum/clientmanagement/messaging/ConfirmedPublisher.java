package com.datum.clientmanagement.messaging;

import com.datum.clientmanagement.config.MessagingProperties;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.datum.clientmanagement.messaging.PublishFailureReason.BROKER_DOWN;
import static com.datum.clientmanagement.messaging.PublishFailureReason.CONFIRM_TIMEOUT;
import static com.datum.clientmanagement.messaging.PublishFailureReason.NACK;
import static com.datum.clientmanagement.messaging.PublishFailureReason.UNROUTABLE;

/**
 * Publishes a CUSTOMER_CREATED JSON payload to {@code customer.events} and blocks until RabbitMQ confirms it
 * (publisher confirms plus mandatory returns). Anything short of a positive, routed confirm within the configured
 * timeout is a {@link PublishFailedException}, so callers can fall back to the outbox (ADR-5).
 */
@Component
class ConfirmedPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    ConfirmedPublisher(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = properties.confirmTimeout();
    }

    void publish(UUID eventId, String jsonPayload) {
        CorrelationData correlation = new CorrelationData(eventId.toString());
        try {
            rabbitTemplate.send(MessagingTopology.CUSTOMER_EVENTS_EXCHANGE,
                    MessagingTopology.CUSTOMER_CREATED_ROUTING_KEY, toMessage(eventId, jsonPayload), correlation);
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            requireConfirmedAndRouted(confirm, correlation.getReturned());
        } catch (AmqpException exception) {
            throw new PublishFailedException(BROKER_DOWN, "RabbitMQ publish failed: " + exception.getMessage(),
                    exception);
        } catch (TimeoutException exception) {
            throw new PublishFailedException(CONFIRM_TIMEOUT, "No publisher confirm within " + confirmTimeout,
                    exception);
        } catch (ExecutionException exception) {
            throw new PublishFailedException(CONFIRM_TIMEOUT, "Publisher confirm failed: " + exception.getMessage(),
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PublishFailedException(CONFIRM_TIMEOUT, "Interrupted while waiting for the publisher confirm",
                    exception);
        }
    }

    // Spring AMQP sets the returned message before it completes the confirm future, so it is safe to check here.
    private void requireConfirmedAndRouted(CorrelationData.Confirm confirm, ReturnedMessage returned) {
        if (!confirm.ack()) {
            throw new PublishFailedException(NACK, "RabbitMQ nacked the message: " + confirm.reason());
        }
        if (returned != null) {
            throw new PublishFailedException(UNROUTABLE,
                    "RabbitMQ returned the message as unroutable: " + returned.getReplyText());
        }
    }

    private Message toMessage(UUID eventId, String jsonPayload) {
        return MessageBuilder.withBody(jsonPayload.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setMessageId(eventId.toString())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
    }
}
