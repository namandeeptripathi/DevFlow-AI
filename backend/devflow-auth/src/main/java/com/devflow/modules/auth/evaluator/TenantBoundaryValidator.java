package com.devflow.modules.auth.evaluator;

import java.util.Optional;
import java.util.UUID;

/**
 * Framework-independent port for validating multi-tenant isolation boundaries and resource hierarchy.
 *
 * <p>Enforces tenant isolation by verifying that resources belong to the active organization.
 * A project must belong to the organization specified by the request; cross-tenant references
 * result in immediate authorization denial.
 *
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §2.2, §8, §10</a>
 */
public interface TenantBoundaryValidator {

    /**
     * Verifies that the specified project belongs to the given tenant organization.
     *
     * @param organizationId the UUID of the organization boundary
     * @param projectId      the UUID of the project
     * @return {@code true} if the project exists and belongs to the organization; {@code false} otherwise
     */
    boolean isProjectInOrganization(UUID organizationId, UUID projectId);

    /**
     * Resolves the enclosing tenant organization UUID for an arbitrary sub-resource if known.
     *
     * @param resourceType the type of the resource (e.g. "repo", "task", "kb")
     * @param resourceId   the identifier of the resource
     * @return an {@link Optional} containing the organization UUID, or empty if unknown or not bound
     */
    default Optional<UUID> getResourceOrganizationId(String resourceType, String resourceId) {
        return Optional.empty();
    }
}

