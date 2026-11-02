package com.devflow.modules.auth.evaluator;

import java.util.UUID;

/**
 * Framework-independent port for verifying active tenant organization membership.
 *
 * <p>Validates that the principal holds an active (non-suspended, non-pending, non-revoked)
 * membership in the specified organization.
 *
 * <p>Caller-supplied IDs and attributes are never trusted as proof of membership;
 * the authorization evaluator delegates authoritative verification directly to this port.
 *
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §2.2, §8.2, §10</a>
 */
@FunctionalInterface
public interface MembershipValidator {

    /**
     * Checks whether the user is an active member of the specified organization.
     *
     * @param userId         the UUID of the user
     * @param organizationId the UUID of the tenant organization
     * @return {@code true} if membership exists and is active; {@code false} otherwise
     */
    boolean isActiveMember(UUID userId, UUID organizationId);
}

