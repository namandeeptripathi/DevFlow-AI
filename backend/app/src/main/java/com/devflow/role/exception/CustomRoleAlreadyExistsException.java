package com.devflow.role.exception;

/**
 * Thrown when attempting to create a custom role with a name that already exists in the organization (Stage 7).
 */
public class CustomRoleAlreadyExistsException extends CustomRoleException {

    public CustomRoleAlreadyExistsException(String message) {
        super(message);
    }
}
