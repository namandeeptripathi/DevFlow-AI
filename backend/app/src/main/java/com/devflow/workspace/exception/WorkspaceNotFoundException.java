package com.devflow.workspace.exception;

/**
 * Thrown when a {@link com.devflow.workspace.domain.Workspace} cannot be located
 * for the requested identifier within an organization boundary.
 */
public class WorkspaceNotFoundException extends WorkspaceDomainException {

    public WorkspaceNotFoundException(String message) {
        super(message);
    }
}
