package com.devflow.modules.auth.evaluator;

import com.devflow.modules.auth.context.AuthorizationContext;

import java.util.UUID;

/**
 * Framework-independent port for evaluating resource ownership facts.
 *
 * <p>Ownership is an authorization condition, not a role (§7).
 * It supplements role-based permissions for owner-gated operations (e.g. project deletion
 * by a {@code PROJECT_LEAD}, document deletion, automation rule modification).
 *
 * <p>Missing or unverified ownership facts must fail closed.
 * Ownership is never inferred from caller-supplied request attributes.
 *
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §2.2, §7, §10</a>
 */
@FunctionalInterface
public interface ResourceOwnershipValidator {

    /**
     * Checks whether the user is the recorded owner or creator of the target resource.
     *
     * @param userId       the UUID of the user
     * @param context      the authorization context
     * @param resourceType the domain resource type (e.g. "project", "task", "repo")
     * @param resourceId   the target resource identifier (may be null if context itself represents the resource)
     * @return {@code true} if verified as owner; {@code false} otherwise
     */
    boolean isOwner(UUID userId, AuthorizationContext context, String resourceType, String resourceId);
}

