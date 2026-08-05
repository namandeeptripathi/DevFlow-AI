package com.devflow.auth.exception;

/**
 * Thrown when provided authentication credentials (username/email or password) are invalid.
 */
public class InvalidCredentialsException extends AuthenticationDomainException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
