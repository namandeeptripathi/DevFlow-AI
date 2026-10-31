package com.devflow.team.exception;

/**
 * Base exception for all Team domain failures.
 *
 * <p>Extends {@link RuntimeException} to integrate cleanly with Spring's
 * declarative transaction rollback.
 */
public class TeamDomainException extends RuntimeException {

    public TeamDomainException(String message) {
        super(message);
    }

    public TeamDomainException(String message, Throwable cause) {
        super(message, cause);
    }
}

