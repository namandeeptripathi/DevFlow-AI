package com.devflow.organization.exception;

/**
 * Thrown when an {@link com.devflow.organization.domain.OrganizationMember} record
 * cannot be located for the given organization and user pair.
 *
 * @see OrganizationDomainException
 * @see com.devflow.organization.service.OrganizationMembershipService
 */
public class OrganizationMemberNotFoundException extends OrganizationDomainException {

    public OrganizationMemberNotFoundException(String message) {
        super(message);
    }
}
