package com.devflow.workspace.domain;

/**
 * Enumerates visibility classifications for workspaces within an organization.
 *
 * <p>Determines accessibility scope for organization members:
 * <ul>
 *   <li>{@link #PUBLIC}: Accessible to all active members of the parent organization.</li>
 *   <li>{@link #PRIVATE}: Restricted to organization administrators (ADMIN, OWNER).</li>
 * </ul>
 *
 * <p>Visibility never extends outside the parent organization boundary.
 */
public enum WorkspaceVisibility {

    /** Accessible to all active members of the parent organization. */
    PUBLIC,

    /** Restricted to organization administrators (ADMIN, OWNER). */
    PRIVATE
}
