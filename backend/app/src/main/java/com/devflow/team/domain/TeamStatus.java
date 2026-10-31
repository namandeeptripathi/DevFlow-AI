package com.devflow.team.domain;

/**
 * Enumerates the lifecycle state of a team within a workspace.
 */
public enum TeamStatus {

    /** Fully active team capable of accepting members and being assigned work. */
    ACTIVE,

    /** Archived team; read-only and preserved for historical reference and auditability. */
    ARCHIVED
}

