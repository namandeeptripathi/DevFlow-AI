package com.devflow.team.exception;

/**
 * Thrown when a {@link com.devflow.team.domain.TeamMember} record cannot be located
 * for the requested user and team identifier.
 */
public class TeamMemberNotFoundException extends TeamDomainException {

    public TeamMemberNotFoundException(String message) {
        super(message);
    }
}

