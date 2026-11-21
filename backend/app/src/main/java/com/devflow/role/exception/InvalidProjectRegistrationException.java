package com.devflow.role.exception;

/**
 * Thrown when an attempted project registration violates tenant isolation invariants,
 * such as attempting to register a project that is already bound to another organization.
 */
public class InvalidProjectRegistrationException extends InvalidRoleAssignmentException {

    public InvalidProjectRegistrationException(String message) {
        super(message);
    }

    public InvalidProjectRegistrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
