package com.devflow.modules.auth.evaluator;

import com.devflow.modules.auth.context.AuthorizationContext;
import com.devflow.modules.auth.domain.Permission;

import java.util.Set;
import java.util.UUID;

/**
 * Framework-independent SPI port for resolving permissions granted via custom roles
 * to a principal within an authorization context (Stage 7).
 *
 * <p>Implementations query authoritative storage or authorization caches for active,
 * non-revoked custom role assignments held by the user in the context organization.
 *
 * @see Permission
 * @see AuthorizationContext
 */
@FunctionalInterface
public interface CustomRolePermissionResolver {

    /**
     * Resolves the canonical permissions granted to the user via active custom roles
     * in the specified authorization context.
     *
     * @param userId  the requesting principal UUID
     * @param context target authorization context (containing organizationId)
     * @return set of granted canonical {@link Permission} instances, or empty set if none
     */
    Set<Permission> resolveCustomPermissions(UUID userId, AuthorizationContext context);
}
