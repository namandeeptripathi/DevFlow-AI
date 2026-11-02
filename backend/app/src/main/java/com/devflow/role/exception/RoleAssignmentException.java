package com.devflow.role.exception;

/**
 * Base unchecked exception for role assignment domain failures.
 */
public class RoleAssignmentException extends RuntimeException {

    public RoleAssignmentException(String message) {
        super(message);
    }

    public RoleAssignmentException(String message, Throwable cause) {
        super(message, cause);
    }
}
