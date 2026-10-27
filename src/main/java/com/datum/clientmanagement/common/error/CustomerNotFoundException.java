package com.datum.clientmanagement.common.error;

public class CustomerNotFoundException extends RuntimeException {

    public CustomerNotFoundException(long customerId) {
        super("No customer exists with id " + customerId + ".");
    }
}
