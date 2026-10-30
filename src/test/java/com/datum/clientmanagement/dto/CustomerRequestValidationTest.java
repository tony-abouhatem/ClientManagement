package com.datum.clientmanagement.dto;

import com.datum.clientmanagement.model.enums.CustomerStatus;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerRequestValidationTest {

    private static final String VALID_CPF = "52998224725";
    private static final String VALID_EMAIL = "ana@example.com";
    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();

    private final Validator validator = VALIDATOR_FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void createRequest_isNormalizedOnConstruction() {
        CustomerRequests.Create request =
                new CustomerRequests.Create("  João da Silva  ", " 529.982.247-25 ", "  " + VALID_EMAIL + "\t", null);

        assertThat(request.name()).isEqualTo("João da Silva");
        assertThat(request.cpf()).isEqualTo(VALID_CPF);
        assertThat(request.email()).isEqualTo(VALID_EMAIL);
        assertThat(invalidFields(request)).isEmpty();
    }

    @Test
    void updateRequest_isNormalizedOnConstruction() {
        CustomerRequests.Update request =
                new CustomerRequests.Update(" Ana ", " " + VALID_EMAIL + " ", "ACTIVE", 0L, "529/982/247-25");

        assertThat(request.name()).isEqualTo("Ana");
        assertThat(request.email()).isEqualTo(VALID_EMAIL);
        assertThat(request.cpf()).isEqualTo(VALID_CPF);
        assertThat(invalidFields(request)).isEmpty();
    }

    @Test
    void createRequest_withoutStatus_defaultsToActive() {
        assertThat(new CustomerRequests.Create("Ana", VALID_CPF, VALID_EMAIL, null).statusOrDefault())
                .isEqualTo(CustomerStatus.ACTIVE);
        assertThat(new CustomerRequests.Create("Ana", VALID_CPF, VALID_EMAIL, "INACTIVE").statusOrDefault())
                .isEqualTo(CustomerStatus.INACTIVE);
    }

    @Test
    void createRequest_nameBoundaries() {
        assertThat(invalidFields(new CustomerRequests.Create("a".repeat(150), VALID_CPF, VALID_EMAIL, null))).isEmpty();
        assertThat(invalidFields(new CustomerRequests.Create("a".repeat(151), VALID_CPF, VALID_EMAIL, null)))
                .containsExactly("name");
        assertThat(invalidFields(new CustomerRequests.Create("   ", VALID_CPF, VALID_EMAIL, null))).containsExactly("name");
        assertThat(invalidFields(new CustomerRequests.Create(null, VALID_CPF, VALID_EMAIL, null))).containsExactly("name");
    }

    @Test
    void createRequest_invalidOrMissingFields_areReported() {
        assertThat(invalidFields(new CustomerRequests.Create("Ana", null, VALID_EMAIL, null))).containsExactly("cpf");
        assertThat(invalidFields(new CustomerRequests.Create("Ana", "52998224726", VALID_EMAIL, null))).containsExactly("cpf");
        assertThat(invalidFields(new CustomerRequests.Create("Ana", VALID_CPF, null, null))).containsExactly("email");
        assertThat(invalidFields(new CustomerRequests.Create("Ana", VALID_CPF, "not-an-email", null))).containsExactly("email");
        assertThat(invalidFields(new CustomerRequests.Create("Ana", VALID_CPF, VALID_EMAIL, "FOO"))).containsExactly("status");
        assertThat(invalidFields(new CustomerRequests.Create("Ana", VALID_CPF, VALID_EMAIL, "active"))).containsExactly("status");
    }

    @Test
    void createRequest_emailOverMaxLength_isRejected() {
        String longEmail = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(63) + ".com";

        assertThat(invalidFields(new CustomerRequests.Create("Ana", VALID_CPF, longEmail, null))).containsExactly("email");
    }

    @Test
    void updateRequest_valid_withOrWithoutCpf() {
        assertThat(invalidFields(new CustomerRequests.Update("Ana", VALID_EMAIL, "ACTIVE", 0L, null))).isEmpty();
        CustomerRequests.Update withMaskedCpf = new CustomerRequests.Update("Ana", VALID_EMAIL, "INACTIVE", 3L, "529.982.247-25");

        assertThat(invalidFields(withMaskedCpf)).isEmpty();
        assertThat(withMaskedCpf.cpf()).isEqualTo(VALID_CPF);
        assertThat(withMaskedCpf.customerStatus()).isEqualTo(CustomerStatus.INACTIVE);
    }

    @Test
    void updateRequest_missingVersionAndStatus_areReported() {
        assertThat(invalidFields(new CustomerRequests.Update("Ana", VALID_EMAIL, null, null, null)))
                .containsExactlyInAnyOrder("status", "version");
    }

    @Test
    void updateRequest_invalidFields_areReported() {
        assertThat(invalidFields(new CustomerRequests.Update("", "bad", "FOO", 0L, "123")))
                .containsExactlyInAnyOrder("name", "email", "status", "cpf");
    }

    private Set<String> invalidFields(Object request) {
        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        return violations.stream().map(violation -> violation.getPropertyPath().toString()).collect(Collectors.toSet());
    }
}
