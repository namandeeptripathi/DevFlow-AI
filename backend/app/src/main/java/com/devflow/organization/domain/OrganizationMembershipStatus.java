package com.devflow.organization.domain;

/**
 * Enumerates the lifecycle state of a user's membership in an organization.
 */
public enum OrganizationMembershipStatus {

    /** Fully active membership with active role permissions. */
    ACTIVE,

    /** Membership invitation extended but not yet accepted by the invitee. */
    INVITED,

    /** Membership temporarily or permanently suspended by organization administrators. */
    SUSPENDED
}
