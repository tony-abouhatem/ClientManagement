package com.datum.clientmanagement.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Outbox relay settings ({@code app.outbox.*}, ADR-5, NFR-7). The wait after a failed attempt comes from the
 * Resilience4j retry instance {@code outbox}. Validated at startup so a bad value fails fast.
 */
@Validated
@ConfigurationProperties("app.outbox")
public record OutboxProperties(
	@NotNull Duration relayInterval,
	@Positive int batchSize
) {
}
