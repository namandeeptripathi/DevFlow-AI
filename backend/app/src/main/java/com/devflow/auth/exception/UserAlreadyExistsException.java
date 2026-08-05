package com.devflow.auth.exception;

/**
 * Thrown during registration when email or username is already registered.
 */
public class UserAlreadyExistsException extends AuthenticationDomainException {

    public UserAlreadyExistsException(String message) {
        super(message);
    }
}
