package com.devflow.team.exception;

import com.devflow.organization.exception.OrganizationAccessDeniedException;

/**
 * Thrown when an authenticated user lacks permissions to manage or modify a team.
 *
 * <p>Extends {@link OrganizationAccessDeniedException} so that it is automatically
 * mapped to HTTP 403 Forbidden by the global exception handler.
 *
 * @see OrganizationAccessDeniedException
 */
public class TeamAccessDeniedException extends OrganizationAccessDeniedException {

    public TeamAccessDeniedException(String message) {
        super(message);
    }
}

