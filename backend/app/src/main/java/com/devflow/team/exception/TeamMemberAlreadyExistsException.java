package com.devflow.team.exception;

/**
 * Thrown when an attempt is made to add a user to a {@link com.devflow.team.domain.Team}
 * who is already an active member of that team.
 */
public class TeamMemberAlreadyExistsException extends TeamDomainException {

    public TeamMemberAlreadyExistsException(String message) {
        super(message);
    }
}

