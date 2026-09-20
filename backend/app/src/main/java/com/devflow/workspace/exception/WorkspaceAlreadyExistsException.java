package com.devflow.workspace.exception;

/**
 * Thrown when a workspace cannot be created or renamed because a workspace
 * with the same name already exists within the target organization (case-insensitive).
 */
public class WorkspaceAlreadyExistsException extends WorkspaceDomainException {

    public WorkspaceAlreadyExistsException(String message) {
        super(message);
    }
}
