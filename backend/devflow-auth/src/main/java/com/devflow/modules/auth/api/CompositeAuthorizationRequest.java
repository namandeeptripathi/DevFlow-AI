package com.devflow.modules.auth.api;

import com.devflow.modules.auth.context.AuthorizationContext;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.RoleScope;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable request specification for composite authorization policy evaluation requiring multiple permissions.
 *
 * <p>Encapsulates the requesting principal, the typed {@link AuthorizationContext}, a non-empty set of
 * required permissions evaluated using strict {@code AND} logic, and optional resource identifier and attributes.
 *
 * @param userId      the UUID of the authenticated principal requesting access
 * @param context     the typed authorization context establishing the scope boundary
 * @param permissions the non-empty set of permissions required for the composite operation
 * @param resourceId  an optional identifier of the target resource
 * @param attributes  an unmodifiable map of contextual attributes
 *
 * @see AuthApi
 * @see AuthorizationRequest
 * @see AuthorizationContext
 * @see Permission
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §6.4, §10, §16</a>
 */
public record CompositeAuthorizationRequest(
        UUID userId,
        AuthorizationContext context,
        Set<Permission> permissions,
        String resourceId,
        Map<String, Object> attributes
) implements Serializable {

    public CompositeAuthorizationRequest {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(permissions, "permissions must not be null");

        if (permissions.isEmpty()) {
            throw new IllegalArgumentException("permissions set must not be empty for a composite authorization request");
        }
        for (Permission perm : permissions) {
            Objects.requireNonNull(perm, "Individual permission elements in composite request must not be null");
        }
        permissions = Collections.unmodifiableSet(new LinkedHashSet<>(permissions));
        attributes = (attributes == null || attributes.isEmpty())
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(attributes);
    }

    // ── Platform Scope Factories ─────────────────────────────────────────────

    public static CompositeAuthorizationRequest platform(UUID userId, Collection<Permission> permissions) {
        return new CompositeAuthorizationRequest(userId, AuthorizationContext.platform(), Set.copyOf(permissions), null, Collections.emptyMap());
    }

    // ── Organization Scope Factories ─────────────────────────────────────────

    public static CompositeAuthorizationRequest organization(UUID userId, UUID organizationId, Collection<Permission> permissions) {
        return new CompositeAuthorizationRequest(userId, AuthorizationContext.organization(organizationId), Set.copyOf(permissions), null, Collections.emptyMap());
    }

    public static CompositeAuthorizationRequest organization(UUID userId, UUID organizationId, Collection<Permission> permissions, String resourceId) {
        return new CompositeAuthorizationRequest(userId, AuthorizationContext.organization(organizationId), Set.copyOf(permissions), resourceId, Collections.emptyMap());
    }

    // ── Project Scope Factories ──────────────────────────────────────────────

    public static CompositeAuthorizationRequest project(UUID userId, UUID organizationId, UUID projectId, Collection<Permission> permissions) {
        return new CompositeAuthorizationRequest(userId, AuthorizationContext.project(organizationId, projectId), Set.copyOf(permissions), null, Collections.emptyMap());
    }

    public static CompositeAuthorizationRequest project(UUID userId, UUID organizationId, UUID projectId, Collection<Permission> permissions, String resourceId) {
        return new CompositeAuthorizationRequest(userId, AuthorizationContext.project(organizationId, projectId), Set.copyOf(permissions), resourceId, Collections.emptyMap());
    }

    // ── Generic Factory ──────────────────────────────────────────────────────

    public static CompositeAuthorizationRequest of(UUID userId, AuthorizationContext context, Collection<Permission> permissions) {
        return new CompositeAuthorizationRequest(userId, context, Set.copyOf(permissions), null, Collections.emptyMap());
    }

    public static CompositeAuthorizationRequest of(UUID userId, AuthorizationContext context, Collection<Permission> permissions, String resourceId) {
        return new CompositeAuthorizationRequest(userId, context, Set.copyOf(permissions), resourceId, Collections.emptyMap());
    }

    // ── Query Accessors ──────────────────────────────────────────────────────

    public RoleScope getScope() {
        return context.getScope();
    }

    public Optional<UUID> getOrganizationId() {
        return context.getOrganizationId();
    }

    public Optional<UUID> getProjectId() {
        return context.getProjectId();
    }

    public Optional<String> getResourceId() {
        return Optional.ofNullable(resourceId);
    }
}

