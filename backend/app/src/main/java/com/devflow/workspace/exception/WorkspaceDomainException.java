package com.devflow.workspace.exception;

/**
 * Base exception for all Workspace domain failures.
 *
 * <p>Extends {@link RuntimeException} to integrate cleanly with Spring's
 * declarative transaction rollback and global exception handling.
 */
public class WorkspaceDomainException extends RuntimeException {

    public WorkspaceDomainException(String message) {
        super(message);
    }

    public WorkspaceDomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
