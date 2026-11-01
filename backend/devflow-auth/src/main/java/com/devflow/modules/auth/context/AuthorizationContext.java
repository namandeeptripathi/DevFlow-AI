package com.devflow.modules.auth.context;

import com.devflow.modules.auth.domain.RoleScope;

import java.io.Serializable;
import java.util.Optional;
import java.util.UUID;

/**
 * Sealed interface representing a typed authorization scope context.
 *
 * <p>DevFlow's authorization architecture enforces strict hierarchical boundaries across
 * three distinct scopes:
 * <ul>
 *   <li>{@link PlatformAuthorizationContext}: Deployment-wide operations (no tenant boundary).</li>
 *   <li>{@link OrganizationAuthorizationContext}: Tenant-wide operations (requires {@code organizationId}).</li>
 *   <li>{@link ProjectAuthorizationContext}: Project-specific operations (requires {@code organizationId} boundary and {@code projectId}).</li>
 * </ul>
 *
 * <p>Every context guarantees type safety and invariant validation, ensuring tenant boundaries
 * cannot be bypassed or conflated.
 *
 * @see PlatformAuthorizationContext
 * @see OrganizationAuthorizationContext
 * @see ProjectAuthorizationContext
 * @see RoleScope
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §4.1, §8, §10</a>
 */
public sealed interface AuthorizationContext extends Serializable permits
        PlatformAuthorizationContext,
        OrganizationAuthorizationContext,
        ProjectAuthorizationContext {

    /**
     * Gets the {@link RoleScope} corresponding to this authorization context.
     *
     * @return the role scope
     */
    RoleScope getScope();

    /**
     * Gets the optional tenant organization boundary for this context.
     *
     * @return an {@link Optional} containing the organization UUID, or empty for platform requests
     */
    Optional<UUID> getOrganizationId();

    /**
     * Gets the optional target project identifier for this context.
     *
     * @return an {@link Optional} containing the project UUID, or empty if not project-scoped
     */
    Optional<UUID> getProjectId();

    /**
     * Creates a platform-scoped authorization context.
     *
     * @return the platform authorization context
     */
    static PlatformAuthorizationContext platform() {
        return PlatformAuthorizationContext.instance();
    }

    /**
     * Creates an organization-scoped authorization context.
     *
     * @param organizationId the UUID of the tenant organization
     * @return the organization authorization context
     */
    static OrganizationAuthorizationContext organization(UUID organizationId) {
        return OrganizationAuthorizationContext.of(organizationId);
    }

    /**
     * Creates a project-scoped authorization context bounded to an organization.
     *
     * @param organizationId the UUID of the tenant organization boundary
     * @param projectId      the UUID of the target project
     * @return the project authorization context
     */
    static ProjectAuthorizationContext project(UUID organizationId, UUID projectId) {
        return ProjectAuthorizationContext.of(organizationId, projectId);
    }
}

