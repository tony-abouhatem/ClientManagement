package com.datum.clientmanagement.customer;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/** Turns a {@link CustomerFilter} into a JPA query; the filters present are combined with AND. */
@Component
class CustomerSpecifications {

    private static final String STATUS = "status";
    private static final String NAME_NORMALIZED = "nameNormalized";
    private static final char LIKE_ESCAPE = '\\';
    private static final String LIKE_ANY = "%";

    Specification<Customer> matching(CustomerFilter filter) {
        Specification<Customer> specification = Specification.unrestricted();
        if (filter.status() != null) {
            specification = specification.and(hasStatus(filter.status()));
        }
        if (filter.normalizedName() != null) {
            specification = specification.and(nameContains(filter.normalizedName()));
        }
        return specification;
    }

    /**
     * The LIKE pattern for "contains {@code text}". {@code %}, {@code _} and the escape character itself are
     * escaped so user input is matched literally instead of acting as wildcards.
     */
    String containsPattern(String text) {
        StringBuilder pattern = new StringBuilder(LIKE_ANY);
        for (char character : text.toCharArray()) {
            if (character == LIKE_ESCAPE || character == '%' || character == '_') {
                pattern.append(LIKE_ESCAPE);
            }
            pattern.append(character);
        }
        return pattern.append(LIKE_ANY).toString();
    }

    private Specification<Customer> hasStatus(CustomerStatus status) {
        return (root, query, builder) -> builder.equal(root.get(STATUS), status);
    }

    private Specification<Customer> nameContains(String normalizedName) {
        // Built outside the lambda so the Serializable Specification doesn't capture this bean.
        String pattern = containsPattern(normalizedName);
        return (root, query, builder) -> builder.like(root.get(NAME_NORMALIZED), pattern, LIKE_ESCAPE);
    }
}
