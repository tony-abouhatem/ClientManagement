/*
 * NFR-16 (and ticket 01 AC-5) — docs/PRODUCT.md, docs/backlog/01-foundation-and-local-stack.md.
 *
 * Interfaces under test: src/main/resources/application.yaml, Java sources under src/main/java,
 * typed @ConfigurationProperties records in com.datum.clientmanagement.config.
 *
 * NFR-11 (compose start-up <= 120 s) is not automated here: it needs docker compose on a dev laptop and is
 * verified manually (user decision).
 */
package com.datum.clientmanagement.nonfunctional;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfigurationNfrTest {

    // NFR-16:config / AC-5 (ticket 01)
    // Given application.yaml
    // When it is parsed
    // Then each of these keys has a value of the form "${ENV_NAME:default}":
    //   spring.datasource.url, spring.datasource.username, spring.datasource.password,
    //   spring.rabbitmq.host, spring.rabbitmq.port, spring.rabbitmq.username, spring.rabbitmq.password,
    //   spring.security.oauth2.resourceserver.jwt.issuer-uri (env ISSUER_URI),
    //   the Score base URL (env SCORE_BASE_URL), Score connect/read timeouts, retry attempts/backoff,
    //   circuit-breaker parameters, the outbox relay interval (env OUTBOX_RELAY_INTERVAL) and confirm timeout
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should expose every URL, credential, timeout and interval as an env-overridable placeholder in application.yaml")
    void nfr16_applicationYaml_usesEnvPlaceholders() { }

    // NFR-16:config / AC-5 (ticket 01)
    // Given all .java files under src/main/java
    // When their contents are scanned
    // Then none contains "http://", "https://", "jdbc:", "amqp://", "localhost", "password=" or a literal
    //      Duration/timeout such as "Duration.ofSeconds(" / "Duration.ofMillis(" outside @ConfigurationProperties defaults
    //      (allow-list: none)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should contain no hard-coded URLs, credentials or timeouts in Java sources")
    void nfr16_javaSources_haveNoHardCodedConfig() { }

    // NFR-16:config
    // Given env/property override SCORE_BASE_URL=http://localhost:<wiremock-port> and OUTBOX_RELAY_INTERVAL=1s
    // When the context starts
    // Then the bound Score properties record has baseUrl "http://localhost:<wiremock-port>"
    //  And the outbox properties record has relay interval 1 s
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should bind overridden environment values into the typed configuration properties")
    void nfr16_envOverridesAreBound() { }
}
