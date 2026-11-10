package com.devflow.security.monitoring.exception;

/**
 * Exception thrown when a security alert is not found in the tenant scope.
 */
public class SecurityAlertNotFoundException extends RuntimeException {

    public SecurityAlertNotFoundException(String message) {
        super(message);
    }
}
