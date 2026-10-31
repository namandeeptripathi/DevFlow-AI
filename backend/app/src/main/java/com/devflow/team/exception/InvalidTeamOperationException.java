package com.devflow.team.exception;

/**
 * Thrown when an invalid domain operation is attempted on a {@link com.devflow.team.domain.Team}
 * or {@link com.devflow.team.domain.TeamMember}, such as modifying an archived team or performing
 * an illegal lifecycle transition.
 */
public class InvalidTeamOperationException extends TeamDomainException {

    public InvalidTeamOperationException(String message) {
        super(message);
    }
}

