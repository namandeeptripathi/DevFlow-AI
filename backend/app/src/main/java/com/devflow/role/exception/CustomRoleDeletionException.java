package com.devflow.role.exception;

/**
 * Thrown when attempting to delete a custom role that still has active assignments (Stage 7).
 */
public class CustomRoleDeletionException extends CustomRoleException {

    public CustomRoleDeletionException(String message) {
        super(message);
    }
}
