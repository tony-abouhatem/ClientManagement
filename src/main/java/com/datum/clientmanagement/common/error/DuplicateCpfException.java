package com.datum.clientmanagement.common.error;

/** Another customer already has this CPF. The message never contains the CPF (NFR-3). */
public class DuplicateCpfException extends RuntimeException {

    private static final String MESSAGE = "A customer with this CPF already exists.";

    public DuplicateCpfException() {
        super(MESSAGE);
    }

    public DuplicateCpfException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
