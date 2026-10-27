package com.datum.clientmanagement.customer;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerFormatsTest {

    @Nested
    class NormalizeCpf {

        @ParameterizedTest
        @ValueSource(strings = {"52998224725", "529.982.247-25", "  529.982247-25 ", "529 982 247 25", "\t52998224725\n",
                "529/982/247-25", "CPF: 529.982.247-25"})
        void anyNonDigitCharacters_areRemoved(String cpf) {
            assertThat(CustomerFormats.normalizeCpf(cpf)).isEqualTo("52998224725");
        }

        @Test
        void nonAsciiDigits_areRemovedToo() {
            assertThat(CustomerFormats.normalizeCpf("５２９９８２２４７２５")).isEmpty();
        }

        @Test
        void null_returnsNull() {
            assertThat(CustomerFormats.normalizeCpf(null)).isNull();
        }
    }

    @Nested
    class MaskCpf {

        @ParameterizedTest
        @ValueSource(strings = {"52998224725", "529.982.247-25"})
        void showsOnlyLastTwoDigits(String cpf) {
            assertThat(CustomerFormats.maskCpf(cpf)).isEqualTo("*********25");
        }

        @Test
        void invalidLengthCpf_stillShowsOnlyLastTwoCharacters() {
            assertThat(CustomerFormats.maskCpf("52998224726000")).isEqualTo("*********00");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"5", "52"})
        void tooShortOrMissing_revealsNothing(String cpf) {
            assertThat(CustomerFormats.maskCpf(cpf)).isEqualTo("*********");
        }
    }

    @Nested
    class SearchableName {

        @ParameterizedTest
        @CsvSource({
                "João da Silva, joao da silva",
                "Élise Março, elise marco",
                "ANA SOUZA, ana souza",
                "Ünal Ñúñez, unal nunez",
                "İpek, ipek",
                "plain, plain"
        })
        void removesAccentsAndLowercases(String name, String expected) {
            assertThat(CustomerFormats.searchableName(name)).isEqualTo(expected);
        }
    }
}
