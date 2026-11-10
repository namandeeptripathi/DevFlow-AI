package com.devflow.security.monitoring.exception;

/**
 * Exception thrown when an audit log event is not found in the specified tenant scope.
 */
public class AuditLogNotFoundException extends RuntimeException {

    public AuditLogNotFoundException(String message) {
        super(message);
    }
}
