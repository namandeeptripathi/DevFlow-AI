package com.devflow.organization.exception;

/**
 * Thrown when an {@link com.devflow.organization.domain.OrganizationInvitation} record
 * cannot be located by token or unique identifier.
 *
 * @see OrganizationDomainException
 * @see com.devflow.organization.service.OrganizationInvitationService
 */
public class OrganizationInvitationNotFoundException extends OrganizationDomainException {

    public OrganizationInvitationNotFoundException(String message) {
        super(message);
    }
}
