package com.datum.clientmanagement.messaging;

import com.datum.clientmanagement.config.ListenerRetryConfiguration;
import com.datum.clientmanagement.config.MessagingTopology;
import com.datum.clientmanagement.exception.InvalidStatusChangeException;
import com.datum.clientmanagement.service.StatusChangeProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code customer.status-change} (ticket 07). Takes the raw message so a bad body becomes an
 * {@link InvalidStatusChangeException} (DLQ, no retry) instead of a converter error. Retries and the final
 * dead-lettering come from the listener container ({@code spring.rabbitmq.listener.simple.retry} and
 * {@link ListenerRetryConfiguration}).
 */
@Component
class StatusChangeListener {

	private final Logger log = LoggerFactory.getLogger(StatusChangeListener.class);

	private final StatusChangeMessageParser parser;
	private final StatusChangeProcessor processor;

	StatusChangeListener(StatusChangeMessageParser parser, StatusChangeProcessor processor) {
		this.parser = parser;
		this.processor = processor;
	}

	@RabbitListener(queues = MessagingTopology.STATUS_CHANGE_QUEUE)
	void onStatusChange(Message message) {
		try {
			processor.process(parser.parse(message));
		} catch (InvalidStatusChangeException exception) {
			log.warn("Status change message rejected, sending it to the DLQ without retry: {}", exception.getMessage());
			throw exception;
		}
	}
}
