package com.devflow.organization.exception;

/**
 * Thrown when an organization cannot be created because the requested slug
 * is already in use by another organization.
 *
 * <p>Typically raised by {@link com.devflow.organization.service.OrganizationService}
 * during organization creation when slug uniqueness validation fails.
 *
 * @see OrganizationDomainException
 * @see com.devflow.organization.service.OrganizationService
 */
public class OrganizationAlreadyExistsException extends OrganizationDomainException {

    public OrganizationAlreadyExistsException(String message) {
        super(message);
    }
}
