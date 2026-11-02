package com.devflow.modules.auth.evaluator;

import java.util.UUID;

/**
 * Framework-independent port for validating platform support access requests (§5.1, §11.1, ADR-001).
 *
 * <p>Enforces the security gate that {@code PLATFORM_SUPPORT} access:
 * <ul>
 *   <li>Must be linked to a verified, open support case.</li>
 *   <li>Must be restricted strictly to the tenant organization associated with that support case.</li>
 *   <li>Must fail closed when the validator is missing or verification cannot be established.</li>
 *   <li>Must never trust caller-supplied request attributes as proof of case authorization.</li>
 * </ul>
 *
 * @see <a href="docs/security/ADR-001-AUTHORIZATION-POLICY-RESOLUTION.md">ADR-001</a>
 */
@FunctionalInterface
public interface PlatformSupportValidator {

    /**
     * Checks whether platform support access is authorized for the specified user, tenant, and case.
     *
     * @param userId               the UUID of the platform support engineer
     * @param targetOrganizationId the UUID of the target tenant organization
     * @param supportCaseId        the identifier of the verified support case
     * @return {@code true} if verified and active; {@code false} otherwise
     */
    boolean isSupportAccessAuthorized(UUID userId, UUID targetOrganizationId, String supportCaseId);
}
