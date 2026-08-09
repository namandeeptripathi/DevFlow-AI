package com.devflow.organization.exception;

/**
 * Thrown when attempting to add a user as a member of an organization when
 * a membership record already exists for that user and organization pair.
 *
 * @see OrganizationDomainException
 * @see com.devflow.organization.service.OrganizationMembershipService
 */
public class OrganizationMemberAlreadyExistsException extends OrganizationDomainException {

    public OrganizationMemberAlreadyExistsException(String message) {
        super(message);
    }
}
