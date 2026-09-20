package com.devflow.workspace.exception;

import com.devflow.organization.exception.OrganizationAccessDeniedException;

/**
 * Thrown when an authenticated user does not have sufficient permissions
 * to access a private workspace within an organization.
 *
 * <p>Extends {@link OrganizationAccessDeniedException} so that it is automatically
 * handled as HTTP 403 Forbidden by the global exception handler.
 */
public class WorkspaceAccessDeniedException extends OrganizationAccessDeniedException {

    public WorkspaceAccessDeniedException(String message) {
        super(message);
    }
}
