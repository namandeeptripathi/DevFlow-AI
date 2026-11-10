package com.devflow.security.monitoring.exception;

/**
 * Exception thrown when a user lacks required authority to access security monitoring APIs.
 */
public class SecurityMonitoringAccessDeniedException extends RuntimeException {

    public SecurityMonitoringAccessDeniedException(String message) {
        super(message);
    }
}
