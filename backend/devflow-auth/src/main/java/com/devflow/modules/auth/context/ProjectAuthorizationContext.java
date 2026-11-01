package com.devflow.modules.auth.context;

import com.devflow.modules.auth.domain.RoleScope;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable authorization context for project-scoped operations.
 *
 * <p>Project requests explicitly declare both the target {@code projectId} and its enclosing
 * {@code organizationId} boundary. This dual specification guarantees that tenant isolation
 * is preserved and verified prior to evaluating project-level roles and permissions.
 *
 * @param organizationId the UUID of the tenant organization boundary
 * @param projectId      the UUID of the target project
 *
 * @see AuthorizationContext
 * @see RoleScope#PROJECT
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §4.1, §4.2, §5.3, §8</a>
 */
public record ProjectAuthorizationContext(UUID organizationId, UUID projectId) implements AuthorizationContext {

    public ProjectAuthorizationContext {
        Objects.requireNonNull(organizationId, "organizationId must not be null for a PROJECT authorization context");
        Objects.requireNonNull(projectId, "projectId must not be null for a PROJECT authorization context");
    }

    /**
     * Creates a project authorization context.
     *
     * @param organizationId the UUID of the organization boundary
     * @param projectId      the UUID of the target project
     * @return the project authorization context
     */
    public static ProjectAuthorizationContext of(UUID organizationId, UUID projectId) {
        return new ProjectAuthorizationContext(organizationId, projectId);
    }

    @Override
    public RoleScope getScope() {
        return RoleScope.PROJECT;
    }

    @Override
    public Optional<UUID> getOrganizationId() {
        return Optional.of(organizationId);
    }

    @Override
    public Optional<UUID> getProjectId() {
        return Optional.of(projectId);
    }

    @Override
    public String toString() {
        return "AuthorizationContext[scope=PROJECT, organizationId=" + organizationId + ", projectId=" + projectId + "]";
    }
}

