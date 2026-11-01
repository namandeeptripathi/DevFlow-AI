package com.devflow.modules.auth.context;

import com.devflow.modules.auth.domain.RoleScope;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable authorization context for tenant-wide organization operations.
 *
 * <p>Organization requests require an explicit, non-null {@code organizationId}
 * establishing the multi-tenant isolation boundary.
 *
 * @param organizationId the UUID of the tenant organization boundary
 *
 * @see AuthorizationContext
 * @see RoleScope#ORGANIZATION
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §4.1, §5.2, §8</a>
 */
public record OrganizationAuthorizationContext(UUID organizationId) implements AuthorizationContext {

    public OrganizationAuthorizationContext {
        Objects.requireNonNull(organizationId, "organizationId must not be null for an ORGANIZATION authorization context");
    }

    /**
     * Creates an organization authorization context.
     *
     * @param organizationId the UUID of the organization
     * @return the organization authorization context
     */
    public static OrganizationAuthorizationContext of(UUID organizationId) {
        return new OrganizationAuthorizationContext(organizationId);
    }

    @Override
    public RoleScope getScope() {
        return RoleScope.ORGANIZATION;
    }

    @Override
    public Optional<UUID> getOrganizationId() {
        return Optional.of(organizationId);
    }

    @Override
    public Optional<UUID> getProjectId() {
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "AuthorizationContext[scope=ORGANIZATION, organizationId=" + organizationId + "]";
    }
}

