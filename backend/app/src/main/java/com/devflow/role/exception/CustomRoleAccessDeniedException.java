package com.devflow.role.exception;

/**
 * Thrown when an actor lacks authority to perform a custom role operation or violates delegation boundaries (Stage 7).
 */
public class CustomRoleAccessDeniedException extends CustomRoleException {

    public CustomRoleAccessDeniedException(String message) {
        super(message);
    }
}
