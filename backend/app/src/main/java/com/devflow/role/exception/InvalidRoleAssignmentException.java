package com.devflow.role.exception;

/**
 * Thrown when an attempted role assignment violates security invariants or domain constraints
 * (e.g. cross-tenant assignment, project assignment without active organization membership,
 * or role scope mismatch).
 */
public class InvalidRoleAssignmentException extends RoleAssignmentException {

    public InvalidRoleAssignmentException(String message) {
        super(message);
    }
}
