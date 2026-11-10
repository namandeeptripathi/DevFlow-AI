package com.devflow.security.monitoring.exception;

/**
 * Exception thrown when a security detection event is not found in the tenant scope.
 */
public class SecurityEventNotFoundException extends RuntimeException {

    public SecurityEventNotFoundException(String message) {
        super(message);
    }
}
