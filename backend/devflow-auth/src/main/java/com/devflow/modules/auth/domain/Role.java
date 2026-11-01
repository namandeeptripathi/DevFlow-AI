package com.devflow.modules.auth.domain;

import java.util.Objects;

/**
 * Enumerates the canonical system roles defined in DevFlow's Role-Based Access Control (RBAC) model.
 *
 * <p>Roles are organized into three distinct hierarchical scopes:
 * <ul>
 *   <li>{@link RoleScope#PLATFORM}: Full-deployment platform administrators and support operators (§5.1).</li>
 *   <li>{@link RoleScope#ORGANIZATION}: Tenant-level governance, administrators, members, viewers, and billing managers (§5.2).</li>
 *   <li>{@link RoleScope#PROJECT}: Fine-grained project leadership, members, and viewers (§5.3).</li>
 * </ul>
 *
 * <p><strong>Approved Role Mapping Note:</strong> The existing application-level
 * {@code com.devflow.organization.domain.OrganizationRole.DEVELOPER} corresponds to
 * {@link #ORGANIZATION_MEMBER} in this central authorization domain.
 *
 * @see RoleScope
 * @see Permission
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §5</a>
 */
public enum Role {

    // ── Platform Roles (§5.1) ────────────────────────────────────────────────
    /**
     * Full platform deployment access including tenant provisioning and emergency access.
     */
    PLATFORM_ADMIN(RoleScope.PLATFORM),

    /**
     * Read-only cross-tenant access for operational support and incident investigation.
     */
    PLATFORM_SUPPORT(RoleScope.PLATFORM),

    // ── Organization Roles (§5.2) ────────────────────────────────────────────
    /**
     * Full administrative control over an organization: billing, ownership, settings, and deletion.
     */
    ORGANIZATION_OWNER(RoleScope.ORGANIZATION),

    /**
     * Administrative access to manage organization members, roles, integrations, and projects.
     */
    ORGANIZATION_ADMIN(RoleScope.ORGANIZATION),

    /**
     * Standard member access to create tasks, contribute to repositories, and interact with AI.
     */
    ORGANIZATION_MEMBER(RoleScope.ORGANIZATION),

    /**
     * Read-only access to organizational dashboards, boards, and non-sensitive data.
     */
    ORGANIZATION_VIEWER(RoleScope.ORGANIZATION),

    /**
     * Access restricted exclusively to subscription, invoice history, and billing management.
     */
    ORGANIZATION_BILLING(RoleScope.ORGANIZATION),

    // ── Project Roles (§5.3) ─────────────────────────────────────────────────
    /**
     * Project management authority: settings, boards, cycles, and member assignment.
     */
    PROJECT_LEAD(RoleScope.PROJECT),

    /**
     * Standard project participant: create and edit tasks, epics, and comments.
     */
    PROJECT_MEMBER(RoleScope.PROJECT),

    /**
     * Read-only observer for project boards, tasks, and epics.
     */
    PROJECT_VIEWER(RoleScope.PROJECT);

    private final RoleScope scope;

    Role(RoleScope scope) {
        this.scope = Objects.requireNonNull(scope, "scope must not be null");
    }

    /**
     * Returns the authorization scope this role belongs to.
     *
     * @return the {@link RoleScope}
     */
    public RoleScope getScope() {
        return scope;
    }

    /**
     * Indicates whether this role operates at the platform scope.
     *
     * @return {@code true} if platform scope; {@code false} otherwise
     */
    public boolean isPlatformRole() {
        return scope == RoleScope.PLATFORM;
    }

    /**
     * Indicates whether this role operates at the organization (tenant) scope.
     *
     * @return {@code true} if organization scope; {@code false} otherwise
     */
    public boolean isOrganizationRole() {
        return scope == RoleScope.ORGANIZATION;
    }

    /**
     * Indicates whether this role operates at the project scope.
     *
     * @return {@code true} if project scope; {@code false} otherwise
     */
    public boolean isProjectRole() {
        return scope == RoleScope.PROJECT;
    }
}

