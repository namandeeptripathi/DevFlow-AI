package com.devflow.role.exception;

/**
 * Thrown when attempting to create a duplicate active role assignment for the same principal
 * within the same tenant or project scope.
 */
public class RoleAssignmentAlreadyExistsException extends RoleAssignmentException {

    public RoleAssignmentAlreadyExistsException(String message) {
        super(message);
    }
}
