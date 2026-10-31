package com.devflow.team.exception;

/**
 * Thrown when a {@link com.devflow.team.domain.Team} cannot be located
 * for the requested identifier within a workspace or organization boundary.
 */
public class TeamNotFoundException extends TeamDomainException {

    public TeamNotFoundException(String message) {
        super(message);
    }
}

