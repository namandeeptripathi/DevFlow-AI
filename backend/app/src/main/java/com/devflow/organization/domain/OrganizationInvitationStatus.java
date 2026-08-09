package com.devflow.organization.domain;

/**
 * Enumerates the lifecycle state of an organization membership invitation.
 */
public enum OrganizationInvitationStatus {

    /** Invitation active and awaiting acceptance by the invitee. */
    PENDING,

    /** Invitation accepted; active membership granted. */
    ACCEPTED,

    /** Invitation passed its validity timestamp without being accepted. */
    EXPIRED,

    /** Invitation explicitly cancelled by an organization admin or owner. */
    REVOKED
}
