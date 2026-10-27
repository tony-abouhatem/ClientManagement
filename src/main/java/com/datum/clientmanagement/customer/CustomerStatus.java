package com.datum.clientmanagement.customer;

public enum CustomerStatus {
    ACTIVE,
    INACTIVE;

    /** Regex accepted for a status in request bodies; kept as a String there so bad values become field errors. */
    static final String PATTERN = "ACTIVE|INACTIVE";
    static final String INVALID_MESSAGE = "must be one of: ACTIVE, INACTIVE";
}
