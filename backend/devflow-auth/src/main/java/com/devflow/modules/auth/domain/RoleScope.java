package com.devflow.modules.auth.domain;

/**
 * Defines the hierarchical authorization scopes within DevFlow.
 *
 * <p>Each scope narrows the authorization boundary and inherits constraints
 * from the parent scope above it:
 * <ul>
 *   <li>{@link #PLATFORM}: Deployment-wide access for platform administrators and internal operators.</li>
 *   <li>{@link #ORGANIZATION}: Tenant-wide access for organization governance, members, and billing.</li>
 *   <li>{@link #PROJECT}: Project-specific access for planning, cycle management, boards, and tasks.</li>
 * </ul>
 *
 * @see Role
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §4.1, §5</a>
 */
public enum RoleScope {

    /**
     * Platform-wide deployment scope for platform administrators and internal support.
     */
    PLATFORM,

    /**
     * Tenant-wide scope for organization governance, members, and billing.
     */
    ORGANIZATION,

    /**
     * Project-specific scope for tasks, boards, and cycles.
     */
    PROJECT
}

