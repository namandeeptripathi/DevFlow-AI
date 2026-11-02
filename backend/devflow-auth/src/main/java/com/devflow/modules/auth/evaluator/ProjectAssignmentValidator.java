package com.devflow.modules.auth.evaluator;

import java.util.UUID;

/**
 * Framework-independent port for validating project membership and assignment facts (§4.2, §5.2, ADR-001).
 *
 * <p>Enforces the rule that an {@code ORGANIZATION_MEMBER} does not receive unrestricted access
 * across all projects in the tenant. Project-scoped access requires a verified project assignment
 * or project role.
 *
 * <p>Missing or unverified project assignment facts fail closed.
 *
 * @see <a href="docs/security/ADR-001-AUTHORIZATION-POLICY-RESOLUTION.md">ADR-001</a>
 */
@FunctionalInterface
public interface ProjectAssignmentValidator {

    /**
     * Checks whether the organization member holds an active project assignment or membership.
     *
     * @param userId         the UUID of the user
     * @param organizationId the UUID of the tenant organization
     * @param projectId      the UUID of the target project
     * @return {@code true} if assigned to the project; {@code false} otherwise
     */
    boolean isAssignedToProject(UUID userId, UUID organizationId, UUID projectId);
}
