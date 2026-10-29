package com.datum.clientmanagement.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * RabbitMQ publishing settings ({@code app.messaging.*}). A publish that is not confirmed within
 * {@code confirmTimeout} counts as failed and goes to the outbox (ADR-5). Validated at startup so a missing value
 * fails fast.
 */
@Validated
@ConfigurationProperties("app.messaging")
public record MessagingProperties(@NotNull Duration confirmTimeout) {
}
