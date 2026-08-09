package com.devflow.organization.exception;

/**
 * Thrown when an operation requires active membership in an organization
 * but the authenticated user is not a member.
 *
 * <p>Mapped to HTTP {@code 403 Forbidden} by {@link com.devflow.exception.GlobalExceptionHandler}.
 *
 * @see OrganizationDomainException
 * @see com.devflow.organization.service.OrganizationAuthorizationService
 */
public class OrganizationMembershipRequiredException extends OrganizationDomainException {

    public OrganizationMembershipRequiredException(String message) {
        super(message);
    }
}
