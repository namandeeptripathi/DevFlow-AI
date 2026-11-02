package com.devflow.modules.auth.evaluator;

import com.devflow.modules.auth.context.AuthorizationContext;
import com.devflow.modules.auth.domain.Permission;

/**
 * Framework-independent port for validating resource lifecycle state constraints.
 *
 * <p>Resource state (e.g. archived, locked, pending deletion) is an independent gate (§4.2, §10)
 * that overrides role permissions and ownership. For example, a user with {@code project.write}
 * cannot modify a project that has been archived.
 *
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §4.2, §10</a>
 */
@FunctionalInterface
public interface ResourceStateValidator {

    /**
     * Checks whether the current lifecycle state of the resource permits the requested operation.
     *
     * @param context      the authorization context
     * @param resourceType the domain resource type
     * @param resourceId   the target resource identifier (may be null if context itself represents the resource)
     * @param permission   the requested permission
     * @return {@code true} if resource state permits the action; {@code false} if state prohibits it
     */
    boolean isOperationPermitted(AuthorizationContext context, String resourceType, String resourceId, Permission permission);
}

