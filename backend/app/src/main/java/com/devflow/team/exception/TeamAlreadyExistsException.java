package com.devflow.team.exception;

/**
 * Thrown when an attempt is made to create or rename a {@link com.devflow.team.domain.Team}
 * with a name that already exists within the target workspace.
 */
public class TeamAlreadyExistsException extends TeamDomainException {

    public TeamAlreadyExistsException(String message) {
        super(message);
    }
}

