package com.datum.clientmanagement.customer;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** The sort orders a client may request on customer listings. */
@Component
class CustomerSort {

    // Static: the static INVALID_SORT_MESSAGE is built from it.
    static final List<String> SORTABLE_PROPERTIES =
            List.of("id", "name", "cpf", "email", "status", "createdAt", "updatedAt");
    // Lists the allowed properties and never echoes the rejected input.
    static final String INVALID_SORT_MESSAGE =
            "Results can only be sorted by: " + String.join(", ", SORTABLE_PROPERTIES) + ".";

    private static final String ID = "id";

    /**
     * Rejects properties outside {@link #SORTABLE_PROPERTIES}, so unknown or internal columns never reach the query.
     * {@code id} ascending is appended unless already requested: it is the default order and keeps pages stable
     * when other sort values are equal.
     */
    Sort validated(Sort requested) {
        for (Sort.Order order : requested) {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_SORT_MESSAGE);
            }
        }
        return requested.getOrderFor(ID) == null ? requested.and(Sort.by(ID)) : requested;
    }
}
