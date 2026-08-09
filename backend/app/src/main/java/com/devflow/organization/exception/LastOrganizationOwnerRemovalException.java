package com.devflow.organization.exception;

/**
 * Thrown when an operation would result in an organization having zero active
 * members with the {@link com.devflow.organization.domain.OrganizationRole#OWNER} role.
 *
 * @see OrganizationDomainException
 * @see com.devflow.organization.service.OrganizationMembershipService
 */
public class LastOrganizationOwnerRemovalException extends OrganizationDomainException {

    public LastOrganizationOwnerRemovalException(String message) {
        super(message);
    }
}
