package com.devflow.organization.exception;

/**
 * Thrown when an operation is requested on an invitation that has already been
 * accepted, revoked, or processed.
 *
 * @see OrganizationDomainException
 * @see com.devflow.organization.service.OrganizationInvitationService
 */
public class OrganizationInvitationAlreadyProcessedException extends OrganizationDomainException {

    public OrganizationInvitationAlreadyProcessedException(String message) {
        super(message);
    }
}
