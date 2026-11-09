package com.devflow.role.exception;

/**
 * Base unchecked exception for custom role and permission governance domain failures (Stage 7).
 */
public class CustomRoleException extends RuntimeException {

    public CustomRoleException(String message) {
        super(message);
    }

    public CustomRoleException(String message, Throwable cause) {
        super(message, cause);
    }
}
