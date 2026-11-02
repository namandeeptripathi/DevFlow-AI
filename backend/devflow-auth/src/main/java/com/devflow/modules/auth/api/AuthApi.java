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

    // ── Composite Authorization Contracts (AND Semantics) ────────────────────

    /**
     * Evaluates a composite authorization request requiring multiple permissions.
     *
     * <p>Enforces strict {@code AND} semantics: all requested permissions must be granted.
     * If any permission is denied, evaluation fails closed and returns the corresponding denial decision.
     *
     * @param request the composite authorization request specification
     * @return the {@link AuthorizationDecision}
     */
    default AuthorizationDecision authorize(CompositeAuthorizationRequest request) {
        if (request == null) {
            return AuthorizationDecision.deny(DenialReason.GENERAL_DENIED, "Composite authorization request must not be null");
        }
        return authorizeAll(
                request.userId(),
                request.context(),
                request.permissions(),
                request.resourceId(),
                request.attributes()
        );
    }

    /**
     * Evaluates whether a composite authorization request is permitted.
     *
     * @param request the composite authorization request specification
     * @return {@code true} if all permissions are allowed; {@code false} otherwise
     */
    default boolean isAuthorized(CompositeAuthorizationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return authorize(request).allowed();
    }

    /**
     * Evaluates whether a user holds all specified permissions within an authorization context.
     *
     * @param userId      the UUID of the user
     * @param context     the authorization context
     * @param permissions the collection of required permissions
     * @return the {@link AuthorizationDecision}
     */
    default AuthorizationDecision authorizeAll(
            UUID userId,
            com.devflow.modules.auth.context.AuthorizationContext context,
            java.util.Collection<Permission> permissions
    ) {
        return authorizeAll(userId, context, permissions, null, java.util.Collections.emptyMap());
    }

    /**
     * Evaluates whether a user holds all specified permissions within an authorization context and target resource.
     *
     * @param userId      the UUID of the user
     * @param context     the authorization context
     * @param permissions the collection of required permissions
     * @param resourceId  the optional sub-resource identifier
     * @return the {@link AuthorizationDecision}
     */
    default AuthorizationDecision authorizeAll(
            UUID userId,
            com.devflow.modules.auth.context.AuthorizationContext context,
            java.util.Collection<Permission> permissions,
            String resourceId
    ) {
        return authorizeAll(userId, context, permissions, resourceId, java.util.Collections.emptyMap());
    }

    /**
     * Evaluates whether a user holds all specified permissions with full contextual attributes.
     *
     * @param userId      the UUID of the user
     * @param context     the authorization context
     * @param permissions the collection of required permissions
     * @param resourceId  the optional sub-resource identifier
     * @param attributes  contextual attributes
     * @return the {@link AuthorizationDecision}
     */
    default AuthorizationDecision authorizeAll(
            UUID userId,
            com.devflow.modules.auth.context.AuthorizationContext context,
            java.util.Collection<Permission> permissions,
            String resourceId,
            java.util.Map<String, Object> attributes
    ) {
        Objects.requireNonNull(permissions, "permissions must not be null");
        if (permissions.isEmpty()) {
            return AuthorizationDecision.deny(DenialReason.GENERAL_DENIED, "No permissions specified for composite check");
        }
        for (Permission permission : permissions) {
            AuthorizationRequest singleRequest = new AuthorizationRequest(userId, context, permission, resourceId, attributes);
            AuthorizationDecision decision = authorize(singleRequest);
            if (!decision.allowed()) {
                return decision;
            }
        }
        return AuthorizationDecision.allow();
    }

    /**
     * Convenience method to check whether a user has all specified permissions in an organization.
     *
     * @param userId         the UUID of the user
     * @param organizationId the UUID of the organization
     * @param permissions    the collection of required permissions
     * @return {@code true} if all permissions are allowed; {@code false} otherwise
     */
    default boolean isAuthorizedAll(UUID userId, UUID organizationId, java.util.Collection<Permission> permissions) {
        return authorizeAll(userId, com.devflow.modules.auth.context.AuthorizationContext.organization(organizationId), permissions).allowed();
    }

    /**
     * Convenience method to check whether a user has all specified permissions in a project.
     *
     * @param userId         the UUID of the user
     * @param organizationId the UUID of the enclosing organization
     * @param projectId      the UUID of the target project
     * @param permissions    the collection of required permissions
     * @return {@code true} if all permissions are allowed; {@code false} otherwise
     */
    default boolean isAuthorizedAll(UUID userId, UUID organizationId, UUID projectId, java.util.Collection<Permission> permissions) {
        return authorizeAll(userId, com.devflow.modules.auth.context.AuthorizationContext.project(organizationId, projectId), permissions).allowed();
    }
}
