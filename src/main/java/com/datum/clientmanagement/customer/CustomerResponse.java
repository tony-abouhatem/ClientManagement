package com.datum.clientmanagement.customer;

import java.time.Instant;

public record CustomerResponse(
        Long id,
        String name,
        String cpf,
        String email,
        CustomerStatus status,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    static CustomerResponse from(Customer customer) {
        return new CustomerResponse(customer.getId(), customer.getName(), customer.getCpf(), customer.getEmail(),
                customer.getStatus(), customer.getVersion(), customer.getCreatedAt(), customer.getUpdatedAt());
    }
}
