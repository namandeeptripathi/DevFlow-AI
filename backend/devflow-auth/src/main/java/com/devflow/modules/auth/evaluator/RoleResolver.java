package com.devflow.modules.auth.evaluator;

import com.devflow.modules.auth.context.AuthorizationContext;
import com.devflow.modules.auth.domain.Role;

import java.util.Set;
import java.util.UUID;

/**
 * Framework-independent port for resolving the active canonical roles assigned to a principal
 * within a given authorization scope and tenant context.
 *
 * <p>Implementations query authoritative persistent storage (e.g. database memberships)
 * or fast-path session state. If no roles are found or the user is not recognized, an empty
 * set must be returned (failing closed).
 *
 * @see Role
 * @see AuthorizationContext
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §2.2, §10</a>
 */
@FunctionalInterface
public interface RoleResolver {

    /**
     * Resolves the canonical roles held by the specified user in the provided authorization context.
     *
     * @param userId  the UUID of the requesting principal
     * @param context the target authorization context
     * @return a set of assigned {@link Role} instances, or an empty set if none found
     */
    Set<Role> resolveRoles(UUID userId, AuthorizationContext context);
}

