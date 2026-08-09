package com.devflow.organization.domain;

/**
 * Enumerates role classifications for users within an organization.
 *
 * <p>Determines administrative and operational permission scope across
 * organization-owned resources and tenant boundary contexts.
 */
public enum OrganizationRole {

    /** Founding owner or primary administrator with complete organization governance rights. */
    OWNER,

    /** Administrative user with broad management capabilities across teams and settings. */
    ADMIN,

    /** Technical team member with read/write access to project repositories and workspaces. */
    DEVELOPER,

    /** Read-only observer with restricted access to organizational resources. */
    VIEWER
}
