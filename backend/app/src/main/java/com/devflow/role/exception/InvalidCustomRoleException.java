package com.devflow.role.exception;

/**
 * Thrown when custom role parameters, names, or states are invalid (Stage 7).
 */
public class InvalidCustomRoleException extends CustomRoleException {

    public InvalidCustomRoleException(String message) {
        super(message);
    }
}
