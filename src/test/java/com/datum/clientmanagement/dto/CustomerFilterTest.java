package com.datum.clientmanagement.dto;

import com.datum.clientmanagement.model.enums.CustomerStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerFilterTest {

    @Test
    void fromQuery_normalizesNameAndParsesStatus() {
        CustomerFilter filter = CustomerFilter.fromQuery("INACTIVE", "  JOÃO da Silva ");

        assertThat(filter.status()).isEqualTo(CustomerStatus.INACTIVE);
        assertThat(filter.normalizedName()).isEqualTo("joao da silva");
    }

    @Test
    void fromQuery_keepsInnerSpacesOfName() {
        assertThat(CustomerFilter.fromQuery(null, "ão d").normalizedName()).isEqualTo("ao d");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void fromQuery_missingOrBlankName_meansNoNameFilter(String name) {
        assertThat(CustomerFilter.fromQuery(null, name)).isEqualTo(new CustomerFilter(null, null));
    }
}
