package com.devflow.role.exception;

/**
 * Thrown when a requested custom role is not found in the specified organization tenant (Stage 7).
 */
public class CustomRoleNotFoundException extends CustomRoleException {

    public CustomRoleNotFoundException(String message) {
        super(message);
    }
}
