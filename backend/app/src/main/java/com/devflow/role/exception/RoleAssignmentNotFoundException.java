package com.devflow.role.exception;

/**
 * Thrown when an expected active role assignment cannot be located.
 */
public class RoleAssignmentNotFoundException extends RoleAssignmentException {

    public RoleAssignmentNotFoundException(String message) {
        super(message);
    }
}
