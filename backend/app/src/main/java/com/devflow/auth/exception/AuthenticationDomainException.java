package com.devflow.auth.exception;

/**
 * Base exception for all authentication domain failures.
 */
public class AuthenticationDomainException extends RuntimeException {

    public AuthenticationDomainException(String message) {
        super(message);
    }

    public AuthenticationDomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
