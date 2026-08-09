package com.devflow.organization.exception;

/**
 * Thrown when an user attempts to accept an organization invitation that has passed
 * its expiration timestamp.
 *
 * @see OrganizationDomainException
 * @see com.devflow.organization.service.OrganizationInvitationService
 */
public class OrganizationInvitationExpiredException extends OrganizationDomainException {

    public OrganizationInvitationExpiredException(String message) {
        super(message);
    }
}
