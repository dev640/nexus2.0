package com.nexus.backend.exception;

/** A generic conflict raised when an account email is already in use. */
public class DuplicateEmailException extends RuntimeException {

    public static final String MESSAGE = "An account with that email already exists";

    public DuplicateEmailException() {
        super(MESSAGE);
    }
}
