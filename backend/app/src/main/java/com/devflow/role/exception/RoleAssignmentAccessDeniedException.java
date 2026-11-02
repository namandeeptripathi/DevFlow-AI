package com.devflow.role.exception;

/**
 * Thrown when an actor attempts to assign or revoke a role without possessing the required
 * administrative or leadership authority (e.g. non-owner attempting to assign or revoke
 * {@code ORGANIZATION_BILLING}, or non-lead attempting to assign project roles).
 */
public class RoleAssignmentAccessDeniedException extends RoleAssignmentException {

    public RoleAssignmentAccessDeniedException(String message) {
        super(message);
    }
}

