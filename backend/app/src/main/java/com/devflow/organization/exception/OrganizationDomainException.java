package com.devflow.organization.exception;

/**
 * Base exception for all Organization domain failures.
 *
 * <p>Extends {@link RuntimeException} to integrate cleanly with Spring's
 * declarative transaction rollback and the global exception handling layer.
 * All domain-specific Organization exceptions must extend this class.
 *
 * @see OrganizationNotFoundException
 * @see OrganizationAlreadyExistsException
 */
public class OrganizationDomainException extends RuntimeException {

    public OrganizationDomainException(String message) {
        super(message);
    }

    public OrganizationDomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
