package com.datum.clientmanagement.messaging;

import com.datum.clientmanagement.exception.PublishFailedException;
import com.datum.clientmanagement.model.PublishFailureReason;
import io.github.resilience4j.core.IntervalBiFunction;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relay backoff as configured in application.yaml (resilience4j.retry.instances.outbox), read the same way
 * OutboxRelay reads it. Only Resilience4j's retry auto-configuration and the real config files are loaded.
 */
class OutboxRetryDelaysTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withConfiguration(AutoConfigurations.of(RetryAutoConfiguration.class));

	@Test
	void outboxRetry_defaultDelaysDoubleFromOneSecondAndAreCappedAtFifteen() {
		contextRunner.run(context -> {
			IntervalBiFunction<Object> delays = context.getBean(RetryRegistry.class)
					.retry(OutboxRelay.RETRY_INSTANCE).getRetryConfig().getIntervalBiFunction();

			assertThat(IntStream.rangeClosed(1, 7).mapToObj(failures -> delayAfter(delays, failures)))
					.containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4),
							Duration.ofSeconds(8), Duration.ofSeconds(15), Duration.ofSeconds(15),
							Duration.ofSeconds(15));
		});
	}

	@Test
	void outboxRetry_waitSettingsAreOverridable() {
		contextRunner
				.withPropertyValues("resilience4j.retry.instances.outbox.wait-duration=100ms",
						"resilience4j.retry.instances.outbox.exponential-backoff-multiplier=3",
						"resilience4j.retry.instances.outbox.exponential-max-wait-duration=1s")
				.run(context -> {
					IntervalBiFunction<Object> delays = context.getBean(RetryRegistry.class)
							.retry(OutboxRelay.RETRY_INSTANCE).getRetryConfig().getIntervalBiFunction();

					assertThat(IntStream.rangeClosed(1, 4).mapToObj(failures -> delayAfter(delays, failures)))
							.containsExactly(Duration.ofMillis(100), Duration.ofMillis(300), Duration.ofMillis(900),
									Duration.ofSeconds(1));
				});
	}

	@Test
	void outboxRetry_defaultMaxAttemptsCoversAboutADayAtTheBackoffCap() {
		contextRunner.run(context -> assertThat(context.getBean(RetryRegistry.class)
				.retry(OutboxRelay.RETRY_INSTANCE).getRetryConfig().getMaxAttempts()).isEqualTo(5760));
	}

	@Test
	void outboxRetry_maxAttemptsIsOverridable() {
		contextRunner.withPropertyValues("resilience4j.retry.instances.outbox.max-attempts=3")
				.run(context -> assertThat(context.getBean(RetryRegistry.class)
						.retry(OutboxRelay.RETRY_INSTANCE).getRetryConfig().getMaxAttempts()).isEqualTo(3));
	}

	private static Duration delayAfter(IntervalBiFunction<Object> delays, int failures) {
		PublishFailedException failure = new PublishFailedException(PublishFailureReason.BROKER_DOWN, "down");
		return Duration.ofMillis(delays.apply(failures, Either.left(failure)));
	}
}
