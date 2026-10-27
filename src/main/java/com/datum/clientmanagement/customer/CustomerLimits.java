package com.datum.clientmanagement.customer;

/** Field limits shared by the request DTOs and the {@link Customer} entity; they match the column sizes in V1__init.sql. */
final class CustomerLimits {

    static final int NAME_MAX_LENGTH = 150;
    static final int EMAIL_MAX_LENGTH = 254;

    private CustomerLimits() {
    }
}
