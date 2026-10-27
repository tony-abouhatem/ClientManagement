package com.datum.clientmanagement.customer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request bodies of the customer endpoints. Each record keeps its own validation rules; both normalize the same way
 * on construction (name and email trimmed, CPF reduced to its digits), so what is validated is what gets stored.
 */
public final class CustomerRequests {

    private CustomerRequests() {
    }

    /** Body of {@code POST /customers}. {@code status} is optional and defaults to ACTIVE. */
    public record Create(
            @NotBlank @Size(max = CustomerLimits.NAME_MAX_LENGTH) String name,
            @NotNull @Cpf String cpf,
            @NotBlank @Email @Size(max = CustomerLimits.EMAIL_MAX_LENGTH) String email,
            @Pattern(regexp = CustomerStatus.PATTERN, message = CustomerStatus.INVALID_MESSAGE) String status) {

        public Create {
            name = strip(name);
            email = strip(email);
            cpf = CustomerFormats.normalizeCpf(cpf);
        }

        CustomerStatus statusOrDefault() {
            return status == null ? CustomerStatus.ACTIVE : CustomerStatus.valueOf(status);
        }
    }

    /**
     * Body of {@code PUT /customers/{id}}. {@code version} is the one the client last read (optimistic lock, ADR-11).
     * {@code cpf} is optional; when present it must match the stored CPF because the CPF is immutable.
     */
    public record Update(
            @NotBlank @Size(max = CustomerLimits.NAME_MAX_LENGTH) String name,
            @NotBlank @Email @Size(max = CustomerLimits.EMAIL_MAX_LENGTH) String email,
            @NotNull @Pattern(regexp = CustomerStatus.PATTERN, message = CustomerStatus.INVALID_MESSAGE) String status,
            @NotNull Long version,
            @Cpf String cpf) {

        public Update {
            name = strip(name);
            email = strip(email);
            cpf = CustomerFormats.normalizeCpf(cpf);
        }

        CustomerStatus customerStatus() {
            return CustomerStatus.valueOf(status);
        }
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }
}
