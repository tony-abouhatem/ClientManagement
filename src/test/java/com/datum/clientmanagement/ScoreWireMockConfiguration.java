package com.datum.clientmanagement;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.Slf4jNotifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * In-JVM WireMock standing in for the external Score service, with {@code app.score.base-url} pointing at it.
 * Importing this class (instead of declaring per-class properties) lets test classes share one application context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ScoreWireMockConfiguration {

    @Bean(destroyMethod = "stop")
    WireMockServer scoreWireMock() {
        // Non-verbose: verbose WireMock logging prints request URLs, which contain the CPF (NFR-3).
        WireMockServer server = new WireMockServer(options().dynamicPort().notifier(new Slf4jNotifier(false)));
        server.start();
        return server;
    }

    @Bean
    DynamicPropertyRegistrar scoreBaseUrl(WireMockServer scoreWireMock) {
        return registry -> registry.add("app.score.base-url", scoreWireMock::baseUrl);
    }
}
