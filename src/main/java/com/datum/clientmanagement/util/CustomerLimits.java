package com.datum.clientmanagement.util;

import com.datum.clientmanagement.model.entity.Customer;

/** Field limits shared by the request DTOs and the {@link Customer} entity; they match the column sizes in V1__init.sql. */
public final class CustomerLimits {

    public static final int NAME_MAX_LENGTH = 150;
    public static final int EMAIL_MAX_LENGTH = 254;

    private CustomerLimits() {
    }
}
