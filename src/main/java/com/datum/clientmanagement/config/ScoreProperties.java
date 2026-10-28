package com.datum.clientmanagement.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * Connection settings of the external Score service ({@code app.score.*}, NFR-4). Retry and circuit-breaker
 * parameters live under {@code resilience4j.*.instances.score}. Validated at startup so a missing value fails fast.
 */
@Validated
@ConfigurationProperties("app.score")
public record ScoreProperties(@NotNull URI baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
}
