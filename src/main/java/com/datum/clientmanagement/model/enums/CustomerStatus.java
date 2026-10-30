package com.datum.clientmanagement.model.enums;

public enum CustomerStatus {
    ACTIVE,
    INACTIVE;

    /** Regex accepted for a status in request bodies; kept as a String there so bad values become field errors. */
    public static final String PATTERN = "ACTIVE|INACTIVE";
    public static final String INVALID_MESSAGE = "must be one of: ACTIVE, INACTIVE";
}
