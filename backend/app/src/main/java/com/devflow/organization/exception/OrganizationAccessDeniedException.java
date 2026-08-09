package com.devflow.organization.exception;

/**
 * Thrown when an authenticated user does not have sufficient permissions
 * to perform the requested operation on an organization.
 *
 * <p>Mapped to HTTP {@code 403 Forbidden} by {@link com.devflow.exception.GlobalExceptionHandler}.
 *
 * @see OrganizationDomainException
 * @see com.devflow.organization.service.OrganizationAuthorizationService
 */
public class OrganizationAccessDeniedException extends OrganizationDomainException {

    public OrganizationAccessDeniedException(String message) {
        super(message);
    }
}
