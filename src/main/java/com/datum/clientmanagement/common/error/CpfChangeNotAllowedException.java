package com.datum.clientmanagement.common.error;

/** A PUT tried to change the CPF of an existing customer; the CPF is immutable. */
public class CpfChangeNotAllowedException extends RuntimeException {

    public CpfChangeNotAllowedException() {
        super("The CPF of an existing customer cannot be changed.");
    }
}
