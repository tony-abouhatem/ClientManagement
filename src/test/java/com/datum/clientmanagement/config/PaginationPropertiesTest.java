package com.datum.clientmanagement.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class PaginationPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(EnablePaginationProperties.class);

    @Test
    void binding_readsDefaultsFromConfiguration() {
        contextRunner
                .withPropertyValues("app.pagination.default-page=2", "app.pagination.default-size=50")
                .run(context -> assertThat(context.getBean(PaginationProperties.class))
                        .isEqualTo(new PaginationProperties(2, 50)));
    }

    @ParameterizedTest
    @CsvSource({"-1, 20", "0, 0", "0, 101"})
    void binding_outOfRangeDefaults_failsStartup(int defaultPage, int defaultSize) {
        contextRunner
                .withPropertyValues("app.pagination.default-page=" + defaultPage,
                        "app.pagination.default-size=" + defaultSize)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void binding_missingDefaultSize_failsStartup() {
        contextRunner
                .withPropertyValues("app.pagination.default-page=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void orDefault_usesConfiguredValueOnlyWhenParameterIsMissing() {
        PaginationProperties pagination = new PaginationProperties(0, 20);

        assertThat(pagination.pageOrDefault(null)).isZero();
        assertThat(pagination.pageOrDefault(3)).isEqualTo(3);
        assertThat(pagination.sizeOrDefault(null)).isEqualTo(20);
        assertThat(pagination.sizeOrDefault(100)).isEqualTo(100);
    }

    @EnableConfigurationProperties(PaginationProperties.class)
    static class EnablePaginationProperties {
    }
}
