package com.datum.clientmanagement.customer;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerSortTest {

    private final CustomerSort customerSort = new CustomerSort();

    @Test
    void validated_unsorted_defaultsToIdAscending() {
        assertThat(customerSort.validated(Sort.unsorted())).isEqualTo(Sort.by(Sort.Order.asc("id")));
    }

    @Test
    void validated_appendsIdAsTieBreaker() {
        Sort sort = customerSort.validated(Sort.by(Sort.Order.desc("name")));

        assertThat(sort).isEqualTo(Sort.by(Sort.Order.desc("name"), Sort.Order.asc("id")));
    }

    @Test
    void validated_keepsRequestedIdDirection() {
        Sort requested = Sort.by(Sort.Order.desc("id"));

        assertThat(customerSort.validated(requested)).isEqualTo(requested);
    }

    @Test
    void validated_acceptsEveryExposedProperty() {
        for (String property : CustomerSort.SORTABLE_PROPERTIES) {
            assertThat(customerSort.validated(Sort.by(property)).getOrderFor(property)).as(property).isNotNull();
        }
    }

    @Test
    void validated_unknownProperty_throwsBadRequestWithoutEchoingIt() {
        assertBadRequest(() -> customerSort.validated(Sort.by("name", "password")));
        assertThat(CustomerSort.INVALID_SORT_MESSAGE)
                .isEqualTo("Results can only be sorted by: id, name, cpf, email, status, createdAt, updatedAt.")
                .doesNotContain("password");
    }

    @Test
    void validated_internalColumn_isRejected() {
        assertBadRequest(() -> customerSort.validated(Sort.by("nameNormalized")));
    }

    private static void assertBadRequest(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getReason()).isEqualTo(CustomerSort.INVALID_SORT_MESSAGE);
        });
    }
}
