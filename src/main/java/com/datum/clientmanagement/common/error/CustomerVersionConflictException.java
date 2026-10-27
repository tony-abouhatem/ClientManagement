package com.datum.clientmanagement.common.error;

/** The version sent by the client is not the stored one: someone else updated the customer first (NFR-12). */
public class CustomerVersionConflictException extends RuntimeException {

    private static final String MESSAGE_TEMPLATE =
            "Customer %d was modified by another request. Fetch it again and retry with the current version.";

    public CustomerVersionConflictException(long customerId) {
        super(MESSAGE_TEMPLATE.formatted(customerId));
    }

    public CustomerVersionConflictException(long customerId, Throwable cause) {
        super(MESSAGE_TEMPLATE.formatted(customerId), cause);
    }
}
