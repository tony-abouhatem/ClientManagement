package com.datum.clientmanagement.repository;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerSpecificationsTest {

    private final CustomerSpecifications specifications = new CustomerSpecifications();

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "joao        | %joao%",
            "ao d        | %ao d%",
            "50%         | %50\\%%",
            "a_b         | %a\\_b%",
            "c:\\temp    | %c:\\\\temp%",
            "' or '1'='1 | %' or '1'='1%"})
    void containsPattern_wrapsInWildcardsAndEscapesLikeSpecialCharacters(String text, String expectedPattern) {
        assertThat(specifications.containsPattern(text)).isEqualTo(expectedPattern);
    }
}
