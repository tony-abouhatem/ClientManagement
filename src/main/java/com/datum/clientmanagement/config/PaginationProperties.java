package com.datum.clientmanagement.config;

import jakarta.validation.constraints.PositiveOrZero;
import org.hibernate.validator.constraints.Range;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Paging defaults of the list endpoints ({@code app.pagination.*}), used when a request omits {@code page} or
 * {@code size} (NFR-14). Validated at startup so a bad value fails fast instead of producing 400s at runtime.
 */
@Validated
@ConfigurationProperties("app.pagination")
public record PaginationProperties(
        @PositiveOrZero int defaultPage,
        @Range(min = PaginationProperties.MIN_SIZE, max = PaginationProperties.MAX_SIZE) int defaultSize) {

    // NFR-14 limits on the requested page size; values outside them are rejected with 400, never clamped.
    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 100;

    public int pageOrDefault(Integer page) {
        return page == null ? defaultPage : page;
    }

    public int sizeOrDefault(Integer size) {
        return size == null ? defaultSize : size;
    }
}
