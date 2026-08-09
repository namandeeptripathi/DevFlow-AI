package com.devflow.organization.exception;

/**
 * Thrown when an {@link com.devflow.organization.domain.Organization} cannot be located
 * for the requested identifier (ID or slug).
 *
 * <p>Typically raised by {@link com.devflow.organization.service.OrganizationService}
 * when a lookup yields no result for a given organization ID or slug.
 *
 * @see OrganizationDomainException
 * @see com.devflow.organization.service.OrganizationService
 */
public class OrganizationNotFoundException extends OrganizationDomainException {

    public OrganizationNotFoundException(String message) {
        super(message);
    }
}
