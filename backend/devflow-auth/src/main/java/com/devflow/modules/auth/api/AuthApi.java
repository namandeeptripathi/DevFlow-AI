package com.devflow.modules.auth.api;

import com.devflow.modules.auth.domain.Permission;

import java.util.Objects;
import java.util.UUID;

/**
 * Public authorization API contract exposed by the {@code devflow-auth} module.
 *
 * <p>This interface is the sole authorization entry point for all domain modules in the
 * DevFlow platform. No domain module implements its own authorization logic; all policy
 * decisions, tenant isolation enforcement, and permission validations are routed through
 * this contract.
 *
 * <p>The contract is protocol-agnostic, separating external transport mechanisms from
 * internal policy evaluation across Platform, Organization, and Project scopes.
 *
 * @see AuthorizationRequest
 * @see AuthorizationDecision
 * @see Permission
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §2.2, §10, §16</a>
 */
public interface AuthApi {

    /**
     * Evaluates an authorization request and returns a detailed decision.
     *
     * @param request the authorization request specification
     * @return the {@link AuthorizationDecision} indicating whether access is allowed or denied with rationale
     */
    AuthorizationDecision authorize(AuthorizationRequest request);

    /**
     * Evaluates whether an authorization request is permitted.
     *
     * @param request the authorization request specification
     * @return {@code true} if allowed; {@code false} otherwise
     */
    default boolean isAuthorized(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return authorize(request).allowed();
    }

    /**
     * Convenience method to evaluate whether a user has a specific permission in a platform scope.
     *
     * @param userId     the UUID of the user
     * @param permission the required permission
     * @return {@code true} if allowed; {@code false} otherwise
     */
    default boolean isAuthorized(UUID userId, Permission permission) {
        return isAuthorized(AuthorizationRequest.platform(userId, permission));
    }

    /**
     * Convenience method to evaluate whether a user has a specific permission in an organization.
     *
     * @param userId         the UUID of the user
     * @param organizationId the UUID of the organization
     * @param permission     the required permission
     * @return {@code true} if allowed; {@code false} otherwise
     */
    default boolean isAuthorized(UUID userId, UUID organizationId, Permission permission) {
        return isAuthorized(AuthorizationRequest.organization(userId, organizationId, permission));
    }

    /**
     * Convenience method to evaluate whether a user has a specific permission in a project.
     *
     * @param userId         the UUID of the user
     * @param organizationId the UUID of the enclosing organization boundary
     * @param projectId      the UUID of the target project
     * @param permission     the required permission
     * @return {@code true} if allowed; {@code false} otherwise
     */
    default boolean isAuthorized(UUID userId, UUID organizationId, UUID projectId, Permission permission) {
        return isAuthorized(AuthorizationRequest.project(userId, organizationId, projectId, permission));
    }
}
