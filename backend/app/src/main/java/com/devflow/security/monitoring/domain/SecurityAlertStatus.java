package com.devflow.security.monitoring.domain;

/**
 * Lifecycle state of a detected security alert.
 */
public enum SecurityAlertStatus {
    OPEN,
    ACKNOWLEDGED,
    RESOLVED
}
