package com.devflow.organization.exception;

/**
 * Thrown when attempting to invite an email address that already has an active
 * pending invitation for the specified organization.
 *
 * @see OrganizationDomainException
 * @see com.devflow.organization.service.OrganizationInvitationService
 */
public class OrganizationInvitationAlreadyExistsException extends OrganizationDomainException {

    public OrganizationInvitationAlreadyExistsException(String message) {
        super(message);
    }
}
