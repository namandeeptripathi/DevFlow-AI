package com.devflow.security.monitoring.exception;

/**
 * Exception thrown when an invalid security operation or state transition is attempted.
 */
public class InvalidSecurityOperationException extends RuntimeException {

    public InvalidSecurityOperationException(String message) {
        super(message);
    }
}
