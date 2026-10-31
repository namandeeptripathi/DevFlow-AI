package com.devflow.team.domain;

/**
 * Enumerates role classifications for users within a team.
 *
 * <p>Determines administrative and operational scope within team contexts:
 * <ul>
 *   <li>{@link #LEAD}: Team leader responsible for team management and administration. Exactly one per active team.</li>
 *   <li>{@link #MEMBER}: Standard team member participating in team-scoped work.</li>
 * </ul>
 */
public enum TeamRole {

    /** Team leader with administrative authority over the team. */
    LEAD,

    /** Standard team member with operational access. */
    MEMBER
}

