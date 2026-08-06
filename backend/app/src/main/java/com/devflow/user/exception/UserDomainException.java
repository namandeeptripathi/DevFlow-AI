package com.devflow.user.exception;

/**
 * Base exception for all User domain failures.
 *
 * <p>Extends {@link RuntimeException} to integrate cleanly with Spring's
 * declarative transaction rollback and the global exception handling layer.
 * All domain-specific User exceptions must extend this class.
 *
 * @see UserProfileNotFoundException
 */
public class UserDomainException extends RuntimeException {

    public UserDomainException(String message) {
        super(message);
    }

    public UserDomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
