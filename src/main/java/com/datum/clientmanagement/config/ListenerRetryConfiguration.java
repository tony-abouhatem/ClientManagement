package com.datum.clientmanagement.config;

import com.datum.clientmanagement.exception.InvalidStatusChangeException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * NFR-9, for every {@code @RabbitListener}: the listener retry ({@code spring.rabbitmq.listener.simple.retry}: 3
 * attempts in total with exponential backoff, then reject to the DLQ) applies only to transient failures. A message
 * rejected as unprocessable ({@link AmqpRejectAndDontRequeueException}, e.g. {@link InvalidStatusChangeException})
 * goes to the DLQ on its first attempt.
 */
@Configuration(proxyBeanMethods = false)
public class ListenerRetryConfiguration {

	// Spring Boot has no property for retry excludes. The retry policy matches them against the whole cause chain,
	// so the container's ListenerExecutionFailedException wrapper does not hide the rejection.
	@Bean
	RabbitListenerRetrySettingsCustomizer rejectedMessagesAreNotRetried() {
		return settings -> settings.setExceptionExcludes(List.of(AmqpRejectAndDontRequeueException.class));
	}
}
